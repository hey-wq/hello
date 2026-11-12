import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from customsdesk.api import create_app
from customsdesk.extract import ClaudeReader, _document_block
from customsdesk.models import Classification, Extraction

ROOT = Path(__file__).resolve().parent.parent
ME = {"actor": "Test Declarant"}


@pytest.fixture
def client(tmp_path):
    return TestClient(create_app(data_dir=tmp_path))


def open_sample(client):
    r = client.post("/api/sample", json=ME)
    assert r.status_code == 200, r.text
    return r.json()


def flag_ids(view):
    return {f["id"] for f in view["shipment"]["flags"]}


def complete(client, sid):
    """Supply what the sample documents leave out."""
    r = client.patch(
        f"/api/shipments/{sid}",
        json=ME
        | {
            "header": {"exchange_rate": 1.08, "freight": 2800},
            "importer": {"eori": "GR000000000"},
            "items": {"1": {"commodity_code": "6203423100"}, "2": {"commodity_code": "6404110000"}},
        },
    )
    assert r.status_code == 200, r.text
    return r.json()


def resolve_all(client, sid, view):
    for f in view["shipment"]["flags"]:
        if f["severity"] != "info" and not f["resolution"]:
            r = client.post(
                f"/api/shipments/{sid}/flags/{f['id']}/resolve",
                json=ME | {"resolution": "importer_confirmed", "note": "Importer sent the bank transfer record."},
            )
            assert r.status_code == 200, r.text
            view = r.json()
    return view


def confirm_all(client, sid, n=3):
    for i in range(n):
        r = client.post(f"/api/shipments/{sid}/items/{i}/confirm", json=ME)
        assert r.status_code == 200, r.text
    return r.json()


def test_sample_raises_the_expected_flags(client):
    view = open_sample(client)
    ids = flag_ids(view)
    assert {"exchange_rate_missing", "freight_missing", "importer_eori_missing"} <= ids
    assert "quantity_mismatch:1" in ids  # invoice 4000 vs packing list 4200
    assert {"code_length:1", "code_length:2"} <= ids and "code_length:0" not in ids
    # No rate yet, so no customs value and no valuation flag.
    assert view["shipment"]["declaration"]["items"][0]["customs_value_eur"] is None
    assert not any(i.startswith("value_") for i in ids)


def test_customs_value_adds_freight_by_weight_and_converts(client):
    view = complete(client, open_sample(client)["shipment"]["id"])
    item = view["shipment"]["declaration"]["items"][0]
    expected = (22200 + 2800 * 2040 / 6450) / 1.08
    assert item["customs_value_eur"] == pytest.approx(expected, abs=0.01)


def test_low_value_is_flagged_and_escalated_for_new_importer(client):
    view = complete(client, open_sample(client)["shipment"]["id"])
    flags = {f["id"]: f for f in view["shipment"]["flags"]}
    low = flags["value_below_reference:1"]
    assert low["severity"] == "high"  # about 1.75 EUR/kg against a low mark of 7.00
    assert "value_below_reference:0" not in flags
    assert flags["new_importer_low_value"]["severity"] == "high"
    assert "exchange_rate_missing" not in flags and "freight_missing" not in flags


def test_cannot_approve_with_open_flags_or_unconfirmed_codes(client):
    sid = open_sample(client)["shipment"]["id"]
    view = complete(client, sid)
    r = client.post(f"/api/shipments/{sid}/approve", json=ME)
    assert r.status_code == 409 and "flag" in r.json()["detail"]

    resolve_all(client, sid, view)
    r = client.post(f"/api/shipments/{sid}/approve", json=ME)
    assert r.status_code == 409 and "confirmed" in r.json()["detail"]

    confirm_all(client, sid)
    r = client.post(f"/api/shipments/{sid}/approve", json=ME)
    assert r.status_code == 200
    assert r.json()["shipment"]["status"] == "approved"


def test_resolution_needs_a_real_note(client):
    sid = open_sample(client)["shipment"]["id"]
    r = client.post(
        f"/api/shipments/{sid}/flags/importer_eori_missing/resolve",
        json=ME | {"resolution": "not_applicable", "note": "ok"},
    )
    assert r.status_code == 409


def test_resolution_is_reopened_when_the_facts_change(client):
    sid = open_sample(client)["shipment"]["id"]
    view = resolve_all(client, sid, complete(client, sid))
    assert view["blocking_flags"] == 0
    # A different freight figure changes the declared unit value, so the earlier answer no longer covers it.
    view = client.patch(f"/api/shipments/{sid}", json=ME | {"header": {"freight": 100}}).json()
    low = next(f for f in view["shipment"]["flags"] if f["id"] == "value_below_reference:1")
    assert low["resolution"] is None
    other = next(f for f in view["shipment"]["flags"] if f["id"] == "quantity_mismatch:1")
    assert other["resolution"] == "importer_confirmed"


def test_changing_a_code_withdraws_its_confirmation(client):
    sid = open_sample(client)["shipment"]["id"]
    complete(client, sid)
    confirm_all(client, sid)
    view = client.patch(f"/api/shipments/{sid}", json=ME | {"items": {"0": {"commodity_code": "6109 10 00 90"}}}).json()
    item = view["shipment"]["declaration"]["items"][0]
    assert item["commodity_code"] == "6109100090"
    assert item["code_confirmed"] is False and item["code_source"] == "declarant"


def test_short_code_cannot_be_confirmed(client):
    sid = open_sample(client)["shipment"]["id"]
    assert client.post(f"/api/shipments/{sid}/items/1/confirm", json=ME).status_code == 409


def approve_sample(client):
    sid = open_sample(client)["shipment"]["id"]
    resolve_all(client, sid, complete(client, sid))
    confirm_all(client, sid)
    assert client.post(f"/api/shipments/{sid}/approve", json=ME).status_code == 200
    return sid


def test_approved_shipment_is_locked_and_exportable(client):
    sid = approve_sample(client)
    assert client.patch(f"/api/shipments/{sid}", json=ME | {"header": {"freight": 1}}).status_code == 409
    exported = client.get(f"/api/shipments/{sid}/declaration.json").json()
    assert exported["approved_by"] == "Test Declarant" and len(exported["items"]) == 3
    pdf = client.get(f"/api/shipments/{sid}/record.pdf")
    assert pdf.status_code == 200 and pdf.content.startswith(b"%PDF")


def test_draft_cannot_be_exported(client):
    sid = open_sample(client)["shipment"]["id"]
    assert client.get(f"/api/shipments/{sid}/declaration.json").status_code == 409


def test_second_shipment_reuses_confirmed_codes_and_knows_the_importer(client):
    approve_sample(client)
    view = open_sample(client)
    items = view["shipment"]["declaration"]["items"]
    assert [i["commodity_code"] for i in items] == ["6109100010", "6203423100", "6404110000"]
    assert all(i["code_source"] == "history" for i in items)
    assert not any(i["code_confirmed"] for i in items)  # still the declarant's call each time
    assert "new_importer" not in flag_ids(view) and "new_importer_low_value" not in flag_ids(view)


def test_record_is_complete_and_tamper_evident(client, tmp_path):
    sid = approve_sample(client)
    view = client.get(f"/api/shipments/{sid}").json()
    types = [e["type"] for e in view["events"]]
    assert types[0] == "shipment_opened" and types[-1] == "declaration_approved"
    assert {"flag_raised", "flag_resolved", "field_changed", "code_confirmed", "flag_cleared"} <= set(types)
    assert view["record_intact"] is True

    import sqlite3

    db = sqlite3.connect(tmp_path / "customsdesk.sqlite3")
    db.execute("UPDATE events SET payload=replace(payload, 'bank transfer', 'phone call') WHERE type='flag_resolved'")
    db.commit()
    assert client.get(f"/api/shipments/{sid}").json()["record_intact"] is False


def test_decline_needs_a_reason_and_locks(client):
    sid = open_sample(client)["shipment"]["id"]
    assert client.post(f"/api/shipments/{sid}/decline", json=ME | {"reason": "no"}).status_code == 409
    r = client.post(f"/api/shipments/{sid}/decline", json=ME | {"reason": "Importer would not provide proof of payment."})
    assert r.json()["shipment"]["status"] == "declined"
    assert client.post(f"/api/shipments/{sid}/approve", json=ME).status_code == 409


def test_invalid_edit_is_refused_and_not_logged(client):
    sid = open_sample(client)["shipment"]["id"]
    before = len(client.get(f"/api/shipments/{sid}").json()["events"])
    r = client.patch(f"/api/shipments/{sid}", json=ME | {"header": {"exchange_rate": "abc"}})
    assert r.status_code == 409
    assert client.patch(f"/api/shipments/{sid}", json=ME | {"header": {"status": "approved"}}).status_code == 409
    assert len(client.get(f"/api/shipments/{sid}").json()["events"]) == before


def test_upload_without_credentials_explains_itself(client):
    r = client.post("/api/shipments", data=ME, files=[("files", ("invoice.pdf", b"%PDF-1.4", "application/pdf"))])
    assert r.status_code == 503 and "sample" in r.json()["detail"]


def test_claude_reader_request_shape():
    sample = json.loads((ROOT / "samples" / "sample_shipment.json").read_text())
    calls = []

    class Response:
        stop_reason = "end_turn"

        def __init__(self, parsed):
            self.parsed_output = parsed

    class Messages:
        def parse(self, **kwargs):
            calls.append(kwargs)
            schema = kwargs["output_format"]
            key = "extraction" if schema is Extraction else "classification"
            return Response(schema.model_validate(sample[key]))

    class Client:
        class beta:
            messages = Messages()

    reader = ClaudeReader(client=Client())
    extraction = reader.read([("invoice.pdf", b"%PDF-1.4"), ("packing.jpg", b"\xff\xd8")])
    reader.classify(extraction, {"mens cotton t shirts": "6109100010"})

    first, second = calls
    blocks = first["messages"][0]["content"]
    assert [b["type"] for b in blocks] == ["document", "image", "text"]
    assert blocks[0]["source"]["media_type"] == "application/pdf"
    assert first["model"] == "claude-sonnet-5-5" and first["thinking"] == {"type": "adaptive"}
    assert second["model"] == "claude-opus-5-5"  # codes always come from the stronger model
    assert "6109100010" in second["messages"][0]["content"][0]["text"]

    reader.read([("invoice.pdf", b"%PDF-1.4")], strong=True)
    assert calls[-1]["model"] == "claude-opus-5-5"

    ClaudeReader(client=Client(), read_model="claude-haiku-5-5").read([("invoice.pdf", b"%PDF-1.4")])
    assert calls[-1]["model"] == "claude-haiku-5-5" and "fallbacks" not in calls[-1]


def test_unsupported_file_type_is_refused():
    with pytest.raises(ValueError):
        _document_block("invoice.docx", b"x")


class TwoPassReader:
    """Stands in for the model: a first reading and, if asked, a stronger one."""

    read_model, strong_model = "cheap", "strong"

    def __init__(self, first, second):
        self.first, self.second, self.passes = first, second, []

    def read(self, documents, strong=False):
        self.passes.append("strong" if strong else "cheap")
        return self.second if strong else self.first

    def classify(self, extraction, history):
        sample = json.loads((ROOT / "samples" / "sample_shipment.json").read_text())
        return Classification.model_validate(sample["classification"])


def clean_reading():
    sample = json.loads((ROOT / "samples" / "sample_shipment.json").read_text())
    extraction = Extraction.model_validate(sample["extraction"])
    extraction.reading_notes = []
    extraction.packing_list.lines[1].quantity = 4000  # agree with the invoice
    return extraction


def upload(tmp_path, reader):
    client = TestClient(create_app(data_dir=tmp_path, reader=reader))
    r = client.post("/api/shipments", data=ME, files=[("files", ("invoice.pdf", b"%PDF-1.4", "application/pdf"))])
    assert r.status_code == 200, r.text
    return r.json()


def read_event(view):
    return next(e["payload"] for e in view["events"] if e["type"] == "documents_read")


def test_clean_reading_is_not_read_twice(tmp_path):
    reader = TwoPassReader(clean_reading(), clean_reading())
    view = upload(tmp_path, reader)
    assert reader.passes == ["cheap"]
    assert read_event(view)["read_by"] == "cheap" and "reread_because" not in read_event(view)


def test_misread_figure_sends_documents_to_the_stronger_model(tmp_path):
    misread = clean_reading()
    misread.invoice.lines[0].line_total = 2220.00  # a dropped digit: 12000 x 1.85 is 22200
    view = upload(tmp_path, TwoPassReader(misread, clean_reading()))
    event = read_event(view)
    assert event["read_by"] == "strong" and event["first_read_by"] == "cheap"
    assert any("does not add up" in reason for reason in event["reread_because"])
    # The declaration is built from the second reading, and its flags are the second reading's.
    assert view["shipment"]["declaration"]["items"][0]["invoice_value"] == 22200.00
    assert "line_arithmetic:0" not in flag_ids(view)


@pytest.mark.parametrize(
    "spoil, expected",
    [
        (lambda e: setattr(e, "legibility", "poor"), "poor"),
        (lambda e: e.reading_notes.append("Stamp covers the total."), "note"),
        (lambda e: setattr(e.invoice, "currency", ""), "currency"),
    ],
)
def test_other_reasons_to_read_again(tmp_path, spoil, expected):
    first = clean_reading()
    spoil(first)
    reader = TwoPassReader(first, clean_reading())
    view = upload(tmp_path, reader)
    assert reader.passes == ["cheap", "strong"]
    assert any(expected in reason for reason in read_event(view)["reread_because"])


def test_no_second_pass_when_only_one_model_is_configured(tmp_path):
    first = clean_reading()
    first.legibility = "poor"
    reader = TwoPassReader(first, clean_reading())
    reader.strong_model = "cheap"
    upload(tmp_path, reader)
    assert reader.passes == ["cheap"]
