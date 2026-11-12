"""HTTP API and the review screen."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import record_pdf
from .auth import Accounts, TooManyAttempts, User
from .checks import Benchmarks
from .extract import ClaudeReader, NoReader, Reader, ReaderNotConfigured
from .models import Classification, Extraction, Representation, Resolution, Shipment, Status
from .service import Desk, NotAllowed
from .store import Store

ROOT = Path(__file__).resolve().parent.parent
WEB = Path(__file__).resolve().parent / "web"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024


COOKIE = "customsdesk_session"


class Credentials(BaseModel):
    username: str = Field(max_length=80)
    password: str = Field(max_length=200)


class Edit(BaseModel):
    header: dict = {}
    importer: dict = {}
    items: dict[int, dict] = {}


class Resolve(BaseModel):
    resolution: Resolution
    note: str


class Decline(BaseModel):
    reason: str


def default_reader() -> Reader:
    if os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"):
        return ClaudeReader()
    return NoReader()


def create_app(
    data_dir: Optional[Path] = None,
    reader: Optional[Reader] = None,
    benchmarks_path: Optional[Path] = None,
) -> FastAPI:
    data_dir = Path(data_dir or os.environ.get("CUSTOMSDESK_DATA", ROOT / "var"))
    benchmarks_path = Path(
        benchmarks_path or os.environ.get("CUSTOMSDESK_BENCHMARKS", ROOT / "data" / "benchmarks_sample.csv")
    )
    reader = reader or default_reader()
    desk = Desk(
        Store(data_dir),
        reader,
        Benchmarks.from_csv(benchmarks_path),
        vat_check=lambda vat: None,  # online VIES validation is not wired up yet
    )
    accounts = Accounts(data_dir)
    accounts.bootstrap_from_environment()
    app = FastAPI(title="Customs desk", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.accounts = accounts

    def signed_in(request: Request) -> User:
        user = accounts.user_for(request.cookies.get(COOKIE))
        if user is None:
            raise HTTPException(401, "Sign in to continue.")
        return user

    @app.post("/api/login")
    def login(body: Credentials, request: Request):
        try:
            token = accounts.sign_in(body.username, body.password)
        except TooManyAttempts:
            raise HTTPException(429, "Too many attempts. Wait a minute and try again.") from None
        if token is None:
            raise HTTPException(401, "The username or password is not right.")
        user = accounts.user_for(token)
        response = JSONResponse({"username": user.username, "name": user.name})
        # Behind a hosting platform's proxy the original scheme arrives in a header.
        https = request.headers.get("x-forwarded-proto", request.url.scheme) == "https"
        response.set_cookie(COOKIE, token, httponly=True, samesite="strict", secure=https, max_age=12 * 60 * 60)
        return response

    @app.post("/api/logout")
    def logout(request: Request):
        accounts.sign_out(request.cookies.get(COOKIE))
        response = JSONResponse({})
        response.delete_cookie(COOKIE)
        return response

    @app.get("/api/me")
    def me(user: User = Depends(signed_in)):
        return {"username": user.username, "name": user.name}

    @app.exception_handler(NotAllowed)
    async def _not_allowed(_, exc: NotAllowed):
        return JSONResponse({"detail": str(exc)}, status_code=409)

    @app.exception_handler(ReaderNotConfigured)
    async def _no_reader(_, exc: ReaderNotConfigured):
        return JSONResponse({"detail": str(exc)}, status_code=503)

    def load(shipment_id: str) -> Shipment:
        shipment = desk.store.get(shipment_id)
        if shipment is None:
            raise HTTPException(404, "Shipment not found.")
        return shipment

    def view(shipment: Shipment) -> dict:
        return {
            "shipment": shipment.model_dump(mode="json"),
            "blocking_flags": len(shipment.blocking_flags()),
            "unconfirmed_items": shipment.unconfirmed_items(),
            "events": desk.store.events(shipment.id),
            "record_intact": desk.store.record_intact(shipment.id),
        }

    @app.get("/api/config")
    def config(user: User = Depends(signed_in)):
        return {
            "reading_configured": not isinstance(reader, NoReader),
            "benchmarks_are_sample": "sample" in benchmarks_path.name,
        }

    @app.get("/api/shipments")
    def list_shipments(user: User = Depends(signed_in)):
        return [
            {
                "id": s.id,
                "created_at": s.created_at,
                "status": s.status.value,
                "importer": s.declaration.importer.name,
                "exporter": s.declaration.exporter.name,
                "invoice_number": s.declaration.invoice_number,
                "items": len(s.declaration.items),
                "blocking_flags": len(s.blocking_flags()),
            }
            for s in desk.store.list()
        ]

    @app.post("/api/shipments")
    async def open_shipment(
        representation: Representation = Form(Representation.DIRECT),
        files: list[UploadFile] = File(...),
        user: User = Depends(signed_in),
    ):
        documents = []
        for upload in files:
            data = await upload.read()
            if len(data) > MAX_UPLOAD_BYTES:
                raise HTTPException(413, f"{upload.filename} is larger than 20 MB.")
            documents.append((upload.filename or "document", data))
        try:
            shipment = desk.open_from_documents(documents, representation, user.name)
        except ReaderNotConfigured:
            raise
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(502, str(exc)) from exc
        return view(shipment)

    @app.post("/api/sample")
    def open_sample(user: User = Depends(signed_in)):
        sample = json.loads((ROOT / "samples" / "sample_shipment.json").read_text(encoding="utf-8"))
        shipment = desk.open(
            Extraction.model_validate(sample["extraction"]),
            Classification.model_validate(sample["classification"]),
            Representation.DIRECT,
            user.name,
        )
        return view(shipment)

    @app.get("/api/shipments/{shipment_id}")
    def get_shipment(shipment_id: str, user: User = Depends(signed_in)):
        return view(load(shipment_id))

    @app.patch("/api/shipments/{shipment_id}")
    def edit_shipment(shipment_id: str, body: Edit, user: User = Depends(signed_in)):
        load(shipment_id)
        return view(desk.edit(shipment_id, user.name, body.header, body.importer, body.items))

    @app.post("/api/shipments/{shipment_id}/items/{item_index}/confirm")
    def confirm_code(shipment_id: str, item_index: int, user: User = Depends(signed_in)):
        load(shipment_id)
        return view(desk.confirm_code(shipment_id, item_index, user.name))

    @app.post("/api/shipments/{shipment_id}/flags/{flag_id}/resolve")
    def resolve_flag(shipment_id: str, flag_id: str, body: Resolve, user: User = Depends(signed_in)):
        load(shipment_id)
        return view(desk.resolve_flag(shipment_id, flag_id, body.resolution, body.note, user.name))

    @app.post("/api/shipments/{shipment_id}/approve")
    def approve(shipment_id: str, user: User = Depends(signed_in)):
        load(shipment_id)
        return view(desk.approve(shipment_id, user.name))

    @app.post("/api/shipments/{shipment_id}/decline")
    def decline(shipment_id: str, body: Decline, user: User = Depends(signed_in)):
        load(shipment_id)
        return view(desk.decline(shipment_id, body.reason, user.name))

    @app.get("/api/shipments/{shipment_id}/declaration.json")
    def export_declaration(shipment_id: str, user: User = Depends(signed_in)):
        shipment = load(shipment_id)
        if shipment.status != Status.APPROVED:
            raise NotAllowed("Only an approved declaration can be exported.")
        return JSONResponse(
            {"shipment_id": shipment.id, "approved_by": shipment.approved_by}
            | shipment.declaration.model_dump(mode="json"),
            headers={"Content-Disposition": f'attachment; filename="declaration-{shipment.id}.json"'},
        )

    @app.get("/api/shipments/{shipment_id}/record.pdf")
    def export_record(shipment_id: str, user: User = Depends(signed_in)):
        shipment = load(shipment_id)
        pdf = record_pdf.render(shipment, desk.store.events(shipment_id), desk.store.record_intact(shipment_id))
        return Response(
            pdf,
            media_type="application/pdf",
            headers={"Content-Disposition": f'attachment; filename="record-{shipment.id}.pdf"'},
        )

    @app.get("/")
    def index():
        return FileResponse(WEB / "index.html", headers={"Cache-Control": "no-cache"})

    # The service worker and manifest are served from the root so the app's scope is the whole site.
    @app.get("/sw.js")
    def service_worker():
        return FileResponse(WEB / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})

    @app.get("/manifest.webmanifest")
    def manifest():
        return FileResponse(WEB / "manifest.webmanifest", media_type="application/manifest+json")

    app.mount("/static", StaticFiles(directory=WEB), name="static")
    return app
