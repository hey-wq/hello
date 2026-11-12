"""Reading documents and suggesting tariff codes.

``ClaudeReader`` sends the invoice and packing list to Claude and gets back a
validated ``Extraction``. A second call suggests a tariff code per line, with
reasoning the declarant can check. Both are suggestions: nothing here is
treated as final until a declarant confirms it.
"""

from __future__ import annotations

import base64
from typing import Protocol

from .models import Classification, Extraction

MODEL = "claude-opus-5-5"

MEDIA_TYPES = {
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
}

EXTRACT_SYSTEM = """\
You read commercial documents for a licensed customs broker in Greece who is \
preparing an import declaration. The broker is legally responsible for what is \
declared, so accuracy matters more than completeness.

Transcribe what the documents say. Do not infer, correct or complete values: \
if a field is absent or illegible, leave it empty and say so in reading_notes. \
If the invoice and the packing list disagree, record each document's own \
figures as written and describe the disagreement in reading_notes. Amounts are \
plain numbers in the invoice currency, without thousands separators. \
Countries are ISO alpha-2 codes. Keep invoice lines and packing lines in the \
order they appear."""

CLASSIFY_SYSTEM = """\
You suggest EU Combined Nomenclature / TARIC commodity codes for goods on an \
import declaration, for a licensed customs broker who will check each one \
before filing. A wrong code changes the duty owed and the broker is \
responsible for it.

For each line give the most specific code you can justify from the \
description, and explain the choice in terms of the heading and subheading \
wording and the General Interpretative Rules, so the broker can verify it. \
Set confidence to "low" whenever the description lacks something the \
nomenclature depends on (material composition, knitted versus woven, gender, \
intended use, dimensions), say what is missing, and list the alternative \
codes that would apply. Where earlier confirmed codes for the same importer \
are supplied, prefer them when the goods are the same."""


class Reader(Protocol):
    def read(self, documents: list[tuple[str, bytes]]) -> Extraction: ...

    def classify(self, extraction: Extraction, history: dict[str, str]) -> Classification: ...


class ReaderNotConfigured(RuntimeError):
    pass


def _document_block(filename: str, data: bytes) -> dict:
    suffix = "." + filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    media_type = MEDIA_TYPES.get(suffix)
    if media_type is None:
        raise ValueError(f"Unsupported file type: {filename}. Use PDF, PNG, JPEG or WebP.")
    encoded = base64.standard_b64encode(data).decode("ascii")
    kind = "document" if media_type == "application/pdf" else "image"
    return {"type": kind, "source": {"type": "base64", "media_type": media_type, "data": encoded}}


class ClaudeReader:
    def __init__(self, client=None):
        if client is None:
            import anthropic

            client = anthropic.Anthropic()
        self.client = client

    def _parse(self, system: str, content: list[dict], schema):
        response = self.client.beta.messages.parse(
            model=MODEL,
            max_tokens=16000,
            system=system,
            thinking={"type": "adaptive"},
            output_config={"effort": "high"},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            messages=[{"role": "user", "content": content}],
            output_format=schema,
        )
        if response.stop_reason == "refusal":
            raise RuntimeError("The model declined to process these documents.")
        if response.stop_reason == "max_tokens" or response.parsed_output is None:
            raise RuntimeError("The model's answer was cut off; the documents may be too long.")
        return response.parsed_output

    def read(self, documents: list[tuple[str, bytes]]) -> Extraction:
        content = [_document_block(name, data) for name, data in documents]
        content.append(
            {
                "type": "text",
                "text": "These are the commercial invoice and packing list for one shipment. "
                "Transcribe them into the schema.",
            }
        )
        return self._parse(EXTRACT_SYSTEM, content, Extraction)

    def classify(self, extraction: Extraction, history: dict[str, str]) -> Classification:
        inv = extraction.invoice
        lines = "\n".join(
            f"{i}. {line.description} | qty {line.quantity} {line.unit} | origin "
            f"{line.origin_country or 'not stated'} | code on document: "
            f"{line.hs_code_on_document or 'none'}"
            for i, line in enumerate(inv.lines)
        )
        known = "\n".join(f"- {desc}: {code}" for desc, code in history.items()) or "none"
        text = (
            f"Seller: {inv.seller.name} ({inv.seller.country})\n"
            f"Buyer: {inv.buyer.name} ({inv.buyer.country})\n\n"
            f"Invoice lines (zero-based index first):\n{lines}\n\n"
            f"Codes this broker has previously confirmed for this importer:\n{known}\n\n"
            "Suggest one commodity code per line."
        )
        return self._parse(CLASSIFY_SYSTEM, [{"type": "text", "text": text}], Classification)


class NoReader:
    """Used when no API credentials are configured."""

    def read(self, documents):
        raise ReaderNotConfigured(
            "Document reading is not configured. Set ANTHROPIC_API_KEY and restart, "
            "or load the sample shipment."
        )

    def classify(self, extraction, history):
        raise ReaderNotConfigured("Document reading is not configured.")
