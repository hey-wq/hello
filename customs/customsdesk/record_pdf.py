"""The due-diligence file for one shipment, as a PDF a broker can hand over."""

from __future__ import annotations

import io
import json
from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from .models import Shipment

# Greek names and notes need a font with Greek glyphs; the built-in PDF fonts have none.
FONT_CANDIDATES = [
    ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    ("/Library/Fonts/Arial Unicode.ttf", "/Library/Fonts/Arial Unicode.ttf"),
    ("C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"),
]


def _fonts() -> tuple[str, str]:
    for regular, bold in FONT_CANDIDATES:
        if Path(regular).exists() and Path(bold).exists():
            if "Desk" not in pdfmetrics.getRegisteredFontNames():
                pdfmetrics.registerFont(TTFont("Desk", regular))
                pdfmetrics.registerFont(TTFont("Desk-Bold", bold))
            return "Desk", "Desk-Bold"
    return "Helvetica", "Helvetica-Bold"


EVENT_LABELS = {
    "shipment_opened": "Shipment opened",
    "document_received": "Document received",
    "documents_read": "Documents read",
    "code_proposed": "Commodity code proposed",
    "code_confirmed": "Commodity code confirmed",
    "field_changed": "Field changed",
    "flag_raised": "Flag raised",
    "flag_cleared": "Flag cleared",
    "flag_resolved": "Flag resolved",
    "declaration_approved": "Declaration approved",
    "job_declined": "Job declined",
}


def render(shipment: Shipment, events: list[dict], intact: bool) -> bytes:
    regular, bold = _fonts()
    body = ParagraphStyle("body", fontName=regular, fontSize=8.5, leading=12)
    small = ParagraphStyle("small", parent=body, fontSize=7, leading=9, textColor=colors.HexColor("#555555"))
    h1 = ParagraphStyle("h1", fontName=bold, fontSize=15, leading=19, spaceAfter=2)
    h2 = ParagraphStyle("h2", fontName=bold, fontSize=10.5, leading=14, spaceBefore=12, spaceAfter=5)

    def p(text, style=body):
        return Paragraph(escape(str(text)), style)

    def table(rows, widths, header=True):
        t = Table(rows, colWidths=widths, repeatRows=1 if header else 0)
        style = [
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LINEBELOW", (0, 0), (-1, -1), 0.25, colors.HexColor("#cccccc")),
            ("LEFTPADDING", (0, 0), (-1, -1), 3),
            ("RIGHTPADDING", (0, 0), (-1, -1), 3),
        ]
        if header:
            style.append(("LINEBELOW", (0, 0), (-1, 0), 0.75, colors.black))
        t.setStyle(TableStyle(style))
        return t

    d = shipment.declaration
    bolded = ParagraphStyle("b", parent=body, fontName=bold)
    story = [
        p("Due-diligence record", h1),
        p(f"Shipment {shipment.id} · opened {shipment.created_at} · status: {shipment.status.value}", small),
        p("Parties and terms", h2),
        table(
            [
                [p("Importer"), p(f"{d.importer.name} · VAT {d.importer.vat_number or '—'} · EORI {d.importer.eori or '—'}")],
                [p("Exporter"), p(f"{d.exporter.name} ({d.exporter.country})")],
                [p("Invoice"), p(f"{d.invoice_number} dated {d.invoice_date} · {d.invoice_total:,.2f} {d.currency}")],
                [p("Terms"), p(f"{d.incoterm} {d.incoterm_place} · freight {d.freight if d.freight is not None else '—'} · rate {d.exchange_rate or '—'}")],
                [p("Representation"), p(d.representation.value)],
                [p("Decision"), p(f"Approved by {shipment.approved_by}" if shipment.approved_by else (f"Declined: {shipment.decline_reason}" if shipment.decline_reason else "Pending"))],
            ],
            [32 * mm, 138 * mm],
            header=False,
        ),
        p("Items", h2),
        table(
            [[p(h, bolded) for h in ("#", "Description", "Code", "Origin", "Net kg", "Customs value €", "Code confirmed")]]
            + [
                [
                    p(i + 1),
                    p(it.description),
                    p(it.commodity_code or "—"),
                    p(it.origin_country or "—"),
                    p(f"{it.net_kg:g}"),
                    p(f"{it.customs_value_eur:,.2f}" if it.customs_value_eur is not None else "—"),
                    p("yes" if it.code_confirmed else "no"),
                ]
                for i, it in enumerate(d.items)
            ],
            [8 * mm, 62 * mm, 24 * mm, 14 * mm, 16 * mm, 26 * mm, 20 * mm],
        ),
        p("Flags and how each was dealt with", h2),
    ]

    flags = [f for f in shipment.flags if f.severity.value != "info"]
    if flags:
        rows = [[p(h, bolded) for h in ("Flag", "What was found", "Resolution")]]
        for f in flags:
            item = f" (item {f.item_index + 1})" if f.item_index is not None else ""
            outcome = (
                f"{f.resolution.value.replace('_', ' ')} — {f.resolution_note} ({f.resolved_by})"
                if f.resolution
                else "UNRESOLVED"
            )
            rows.append([p(f"{f.title}{item} [{f.severity.value}]"), p(f.detail), p(outcome)])
        story.append(table(rows, [45 * mm, 65 * mm, 60 * mm]))
    else:
        story.append(p("No flags were raised."))

    story.append(p("Event log", h2))
    story.append(
        p(
            "Each entry carries the hash of the entry before it. "
            + ("The chain verifies: no entry has been altered or removed." if intact else "WARNING: the chain does NOT verify."),
            small,
        )
    )
    story.append(Spacer(1, 4))
    rows = [[p(h, bolded) for h in ("Time (UTC)", "By", "Event", "Details", "Hash")]]
    for e in events:
        details = "; ".join(
            f"{k}: {json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v}"
            for k, v in e["payload"].items()
        )
        rows.append(
            [
                p(e["at"].replace("+00:00", "").replace("T", " "), small),
                p(e["actor"], small),
                p(EVENT_LABELS.get(e["type"], e["type"]), small),
                p(details, small),
                p(e["hash"][:12], small),
            ]
        )
    story.append(table(rows, [27 * mm, 22 * mm, 30 * mm, 71 * mm, 20 * mm]))

    buffer = io.BytesIO()
    SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=20 * mm,
        rightMargin=20 * mm,
        topMargin=18 * mm,
        bottomMargin=18 * mm,
        title=f"Due-diligence record {shipment.id}",
    ).build(story)
    return buffer.getvalue()
