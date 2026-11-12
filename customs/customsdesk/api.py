"""HTTP API and the review screen."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import record_pdf
from .checks import Benchmarks
from .extract import ClaudeReader, NoReader, Reader, ReaderNotConfigured
from .models import Classification, Extraction, Representation, Resolution, Shipment, Status
from .service import Desk, NotAllowed
from .store import Store

ROOT = Path(__file__).resolve().parent.parent
WEB = Path(__file__).resolve().parent / "web"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024


class Actor(BaseModel):
    actor: str = Field(min_length=2, max_length=80)


class Edit(Actor):
    header: dict = {}
    importer: dict = {}
    items: dict[int, dict] = {}


class Resolve(Actor):
    resolution: Resolution
    note: str


class Decline(Actor):
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
    app = FastAPI(title="Customs desk", docs_url=None, redoc_url=None)

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
    def config():
        return {
            "reading_configured": not isinstance(reader, NoReader),
            "benchmarks_are_sample": "sample" in benchmarks_path.name,
        }

    @app.get("/api/shipments")
    def list_shipments():
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
        actor: str = Form(min_length=2, max_length=80),
        representation: Representation = Form(Representation.DIRECT),
        files: list[UploadFile] = File(...),
    ):
        documents = []
        for upload in files:
            data = await upload.read()
            if len(data) > MAX_UPLOAD_BYTES:
                raise HTTPException(413, f"{upload.filename} is larger than 20 MB.")
            documents.append((upload.filename or "document", data))
        try:
            shipment = desk.open_from_documents(documents, representation, actor)
        except ReaderNotConfigured:
            raise
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(502, str(exc)) from exc
        return view(shipment)

    @app.post("/api/sample")
    def open_sample(body: Actor):
        sample = json.loads((ROOT / "samples" / "sample_shipment.json").read_text(encoding="utf-8"))
        shipment = desk.open(
            Extraction.model_validate(sample["extraction"]),
            Classification.model_validate(sample["classification"]),
            Representation.DIRECT,
            body.actor,
        )
        return view(shipment)

    @app.get("/api/shipments/{shipment_id}")
    def get_shipment(shipment_id: str):
        return view(load(shipment_id))

    @app.patch("/api/shipments/{shipment_id}")
    def edit_shipment(shipment_id: str, body: Edit):
        load(shipment_id)
        return view(desk.edit(shipment_id, body.actor, body.header, body.importer, body.items))

    @app.post("/api/shipments/{shipment_id}/items/{item_index}/confirm")
    def confirm_code(shipment_id: str, item_index: int, body: Actor):
        load(shipment_id)
        return view(desk.confirm_code(shipment_id, item_index, body.actor))

    @app.post("/api/shipments/{shipment_id}/flags/{flag_id}/resolve")
    def resolve_flag(shipment_id: str, flag_id: str, body: Resolve):
        load(shipment_id)
        return view(desk.resolve_flag(shipment_id, flag_id, body.resolution, body.note, body.actor))

    @app.post("/api/shipments/{shipment_id}/approve")
    def approve(shipment_id: str, body: Actor):
        load(shipment_id)
        return view(desk.approve(shipment_id, body.actor))

    @app.post("/api/shipments/{shipment_id}/decline")
    def decline(shipment_id: str, body: Decline):
        load(shipment_id)
        return view(desk.decline(shipment_id, body.reason, body.actor))

    @app.get("/api/shipments/{shipment_id}/declaration.json")
    def export_declaration(shipment_id: str):
        shipment = load(shipment_id)
        if shipment.status != Status.APPROVED:
            raise NotAllowed("Only an approved declaration can be exported.")
        return JSONResponse(
            {"shipment_id": shipment.id, "approved_by": shipment.approved_by}
            | shipment.declaration.model_dump(mode="json"),
            headers={"Content-Disposition": f'attachment; filename="declaration-{shipment.id}.json"'},
        )

    @app.get("/api/shipments/{shipment_id}/record.pdf")
    def export_record(shipment_id: str):
        shipment = load(shipment_id)
        pdf = record_pdf.render(shipment, desk.store.events(shipment_id), desk.store.record_intact(shipment_id))
        return Response(
            pdf,
            media_type="application/pdf",
            headers={"Content-Disposition": f'attachment; filename="record-{shipment.id}.pdf"'},
        )

    @app.get("/")
    def index():
        return FileResponse(WEB / "index.html")

    app.mount("/static", StaticFiles(directory=WEB), name="static")
    return app
