"""What a declarant can do with a shipment, and what gets recorded when they do."""

from __future__ import annotations

import uuid
from enum import Enum
from typing import Optional

from pydantic import ValidationError

from . import checks
from .declaration import build_declaration, compute_customs_values, digits, normalise
from .extract import Reader
from .models import (
    Classification,
    Extraction,
    Party,
    Representation,
    Resolution,
    Severity,
    Shipment,
    Status,
)
from .store import Store, now

SYSTEM = "system"

HEADER_FIELDS = {"currency", "exchange_rate", "incoterm", "incoterm_place", "freight", "insurance", "representation"}
IMPORTER_FIELDS = {"name", "vat_number", "eori", "country", "address"}
ITEM_FIELDS = {"description", "quantity", "unit", "origin_country", "packages", "gross_kg", "net_kg", "commodity_code"}


class NotAllowed(Exception):
    """The action is refused; the message says why and is safe to show."""


def importer_key(party: Party) -> str:
    for value in (party.vat_number, party.eori):
        cleaned = value.replace(" ", "").upper()
        if cleaned:
            return cleaned
    return normalise(party.name)


class Desk:
    def __init__(self, store: Store, reader: Reader, benchmarks: checks.Benchmarks, vat_check: checks.VatCheck):
        self.store = store
        self.reader = reader
        self.benchmarks = benchmarks
        self.vat_check = vat_check

    # -- intake ------------------------------------------------------------

    def open_from_documents(
        self,
        documents: list[tuple[str, bytes]],
        representation: Representation,
        actor: str,
    ) -> Shipment:
        extraction = self.reader.read(documents)
        key = importer_key(extraction.invoice.buyer)
        classification = self.reader.classify(extraction, self.store.confirmed_codes(key))
        return self.open(extraction, classification, representation, actor, documents)

    def open(
        self,
        extraction: Extraction,
        classification: Optional[Classification],
        representation: Representation,
        actor: str,
        documents: list[tuple[str, bytes]] = (),
    ) -> Shipment:
        shipment_id = uuid.uuid4().hex[:12]
        key = importer_key(extraction.invoice.buyer)
        declaration = build_declaration(
            extraction, classification, self.store.confirmed_codes(key), representation
        )
        shipment = Shipment(id=shipment_id, created_at=now(), extraction=extraction, declaration=declaration)

        self.store.log(shipment_id, actor, "shipment_opened", representation=representation.value)
        for name, data in documents:
            digest = self.store.save_file(shipment_id, name, data)
            self.store.log(shipment_id, actor, "document_received", filename=name, sha256=digest, bytes=len(data))
        self.store.log(
            shipment_id,
            SYSTEM,
            "documents_read",
            invoice_number=extraction.invoice.number,
            lines=len(extraction.invoice.lines),
            declared_total=extraction.invoice.total,
            currency=extraction.invoice.currency,
        )
        for i, item in enumerate(declaration.items):
            if item.commodity_code:
                self.store.log(
                    shipment_id,
                    SYSTEM,
                    "code_proposed",
                    item=i,
                    code=item.commodity_code,
                    source=item.code_source,
                    confidence=item.code_confidence,
                )
        self._recheck(shipment)
        return shipment

    # -- review ------------------------------------------------------------

    def _editable(self, shipment_id: str) -> Shipment:
        shipment = self.store.get(shipment_id)
        if shipment is None:
            raise KeyError(shipment_id)
        if shipment.status != Status.DRAFT:
            raise NotAllowed(f"This shipment is {shipment.status.value} and can no longer be changed.")
        return shipment

    def _recheck(self, shipment: Shipment) -> None:
        d = shipment.declaration
        key = importer_key(d.importer)
        compute_customs_values(d)
        fresh = checks.run_all(
            shipment.extraction,
            d,
            self.benchmarks,
            lambda code8, origin: self.store.past_values(code8, origin, shipment.id),
            self.store.importer_has_approved(key, shipment.id),
            self.vat_check,
        )
        before = {f.id: f for f in shipment.flags}
        after = {f.id for f in fresh}
        shipment.flags = checks.carry_over(shipment.flags, fresh)

        for f in shipment.flags:
            old = before.get(f.id)
            if old is None or old.evidence != f.evidence:
                self.store.log(
                    shipment.id,
                    SYSTEM,
                    "flag_raised",
                    flag=f.id,
                    severity=f.severity.value,
                    title=f.title,
                    detail=f.detail,
                    evidence=f.evidence,
                )
        for flag_id, old in before.items():
            if flag_id not in after:
                self.store.log(shipment.id, SYSTEM, "flag_cleared", flag=flag_id, title=old.title)
        self.store.save(shipment, key)

    def edit(self, shipment_id: str, actor: str, header: dict, importer: dict, items: dict[int, dict]) -> Shipment:
        shipment = self._editable(shipment_id)
        d = shipment.declaration
        changes: list[dict] = []

        def plain(value):
            return value.value if isinstance(value, Enum) else value

        def apply(target, field: str, value, label: str) -> None:
            old = getattr(target, field)
            if field == "commodity_code":
                value = digits(str(value))
            if field in {"currency", "incoterm", "origin_country", "country"} and isinstance(value, str):
                value = value.strip().upper()
            try:
                # Validates and coerces before assigning, so a bad value is refused, not stored or logged.
                type(target).__pydantic_validator__.validate_assignment(target, field, value)
            except ValidationError as exc:
                raise NotAllowed(f"Invalid value for {label}: {exc.errors()[0]['msg']}.") from exc
            new = getattr(target, field)
            if new != old:
                changes.append({"field": label, "old": plain(old), "new": plain(new)})

        for field, value in header.items():
            if field not in HEADER_FIELDS:
                raise NotAllowed(f"“{field}” cannot be edited.")
            if field == "representation":
                value = Representation(value)
            apply(d, field, value, field)
        for field, value in importer.items():
            if field not in IMPORTER_FIELDS:
                raise NotAllowed(f"“{field}” cannot be edited.")
            apply(d.importer, field, value, f"importer.{field}")
        for index, item_edits in items.items():
            if not 0 <= index < len(d.items):
                raise NotAllowed(f"There is no item {index + 1}.")
            item = d.items[index]
            for field, value in item_edits.items():
                if field not in ITEM_FIELDS:
                    raise NotAllowed(f"“{field}” cannot be edited.")
                code_before = item.commodity_code
                apply(item, field, value, f"item {index + 1}.{field}")
                if field == "commodity_code" and item.commodity_code != code_before:
                    # A changed code needs confirming again. Completing a proposed code to
                    # 10 digits keeps its origin; anything else is the declarant's own.
                    if not (code_before and item.commodity_code.startswith(code_before)):
                        item.code_source = "declarant"
                        item.code_reasoning = ""
                        item.code_confidence = ""
                        item.code_alternatives = []
                    item.code_confirmed = False

        for change in changes:
            self.store.log(shipment_id, actor, "field_changed", **change)
        self._recheck(shipment)
        return shipment

    def confirm_code(self, shipment_id: str, item_index: int, actor: str) -> Shipment:
        shipment = self._editable(shipment_id)
        items = shipment.declaration.items
        if not 0 <= item_index < len(items):
            raise NotAllowed(f"There is no item {item_index + 1}.")
        item = items[item_index]
        if len(item.commodity_code) != 10:
            raise NotAllowed("Complete the 10-digit commodity code before confirming it.")
        item.code_confirmed = True
        self.store.log(
            shipment_id,
            actor,
            "code_confirmed",
            item=item_index,
            code=item.commodity_code,
            source=item.code_source,
        )
        self.store.save(shipment, importer_key(shipment.declaration.importer))
        return shipment

    def resolve_flag(self, shipment_id: str, flag_id: str, resolution: Resolution, note: str, actor: str) -> Shipment:
        shipment = self._editable(shipment_id)
        flag = next((f for f in shipment.flags if f.id == flag_id), None)
        if flag is None:
            raise NotAllowed("That flag no longer exists.")
        if flag.severity == Severity.INFO:
            raise NotAllowed("Information notes do not need resolving.")
        note = note.strip()
        if len(note) < 10:
            raise NotAllowed("Describe what was checked or received, in a sentence or more.")
        flag.resolution = resolution
        flag.resolution_note = note
        flag.resolved_by = actor
        self.store.log(
            shipment_id, actor, "flag_resolved", flag=flag_id, title=flag.title, resolution=resolution.value, note=note
        )
        self.store.save(shipment, importer_key(shipment.declaration.importer))
        return shipment

    # -- decision ----------------------------------------------------------

    def approve(self, shipment_id: str, actor: str) -> Shipment:
        shipment = self._editable(shipment_id)
        open_flags = shipment.blocking_flags()
        if open_flags:
            raise NotAllowed(f"{len(open_flags)} flag(s) still need a recorded resolution.")
        unconfirmed = shipment.unconfirmed_items()
        if unconfirmed:
            raise NotAllowed(f"{len(unconfirmed)} commodity code(s) have not been confirmed.")

        shipment.status = Status.APPROVED
        shipment.approved_by = actor
        key = importer_key(shipment.declaration.importer)
        for item in shipment.declaration.items:
            self.store.remember_code(key, normalise(item.description), item.commodity_code)
            if item.customs_value_eur and item.net_kg:
                self.store.remember_value(
                    shipment_id, item.commodity_code[:8], item.origin_country, item.customs_value_eur / item.net_kg
                )
        self.store.log(
            shipment_id,
            actor,
            "declaration_approved",
            items=len(shipment.declaration.items),
            customs_value_eur=sum(i.customs_value_eur or 0 for i in shipment.declaration.items),
        )
        self.store.save(shipment, key)
        return shipment

    def decline(self, shipment_id: str, reason: str, actor: str) -> Shipment:
        shipment = self._editable(shipment_id)
        reason = reason.strip()
        if len(reason) < 10:
            raise NotAllowed("Give the reason for declining, in a sentence or more.")
        shipment.status = Status.DECLINED
        shipment.decline_reason = reason
        self.store.log(shipment_id, actor, "job_declined", reason=reason)
        self.store.save(shipment, importer_key(shipment.declaration.importer))
        return shipment
