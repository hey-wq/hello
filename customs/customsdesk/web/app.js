"use strict";

const app = document.getElementById("app");
const actorInput = document.getElementById("actor");
const toastEl = document.getElementById("toast");

const RESOLUTIONS = {
  importer_confirmed: "Importer confirmed, with proof",
  corrected: "Corrected in the declaration",
  guarantee_taken: "Guarantee taken",
  not_applicable: "Checked: not a problem",
};
const EVENTS = {
  shipment_opened: "Shipment opened",
  document_received: "Document received",
  documents_read: "Documents read",
  code_proposed: "Code proposed",
  code_confirmed: "Code confirmed",
  field_changed: "Field changed",
  flag_raised: "Flag raised",
  flag_cleared: "Flag cleared",
  flag_resolved: "Flag resolved",
  declaration_approved: "Declaration approved",
  job_declined: "Job declined",
};
const CODE_SOURCES = {
  suggested: "Suggested",
  history: "From your earlier declarations",
  document: "From the supplier's document",
  declarant: "Entered by declarant",
};

// ---- helpers ---------------------------------------------------------------

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key.startsWith("on")) el.addEventListener(key.slice(2), value);
    else if (key === "class") el.className = value;
    else if (key in el && key !== "list") el[key] = value;
    else el.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return el;
}

function toast(message) {
  toastEl.textContent = message;
  toastEl.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (toastEl.hidden = true), 5000);
}

function actor() {
  const name = actorInput.value.trim();
  if (name.length < 2) {
    actorInput.focus();
    throw new Error("Enter your name first. Every action is recorded against it.");
  }
  return name;
}

async function api(method, path, body) {
  const options = { method, headers: {} };
  if (body instanceof FormData) options.body = body;
  else if (body) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  if (!response.ok) {
    let detail = response.statusText;
    try {
      const data = await response.json();
      detail = typeof data.detail === "string" ? data.detail : "The request was not valid.";
    } catch (_) {}
    throw new Error(detail);
  }
  return response.json();
}

async function run(action) {
  try {
    return await action();
  } catch (error) {
    toast(error.message);
    return null;
  }
}

const money = (n) => (n === null || n === undefined ? "—" : n.toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
const when = (iso) => new Date(iso).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });

function chip(text, kind) {
  return h("span", { class: "chip " + (kind || "") }, text);
}

// ---- list ------------------------------------------------------------------

async function showList() {
  const rows = await api("GET", "/api/shipments");
  const body = rows.length
    ? h("div", { class: "card scroll" },
        h("table", {},
          h("thead", {}, h("tr", {}, ["Opened", "Importer", "Exporter", "Invoice", "Items", "Status"].map((t) => h("th", {}, t)))),
          h("tbody", {}, rows.map((s) =>
            h("tr", { class: "link", onclick: () => (location.hash = "#/s/" + s.id) },
              h("td", {}, when(s.created_at)),
              h("td", {}, s.importer || "—"),
              h("td", {}, s.exporter || "—"),
              h("td", {}, s.invoice_number || "—"),
              h("td", {}, s.items),
              h("td", {}, statusChip(s)))))))
    : h("div", { class: "card empty" }, "No shipments yet. Open one from an invoice and packing list.");
  app.replaceChildren(
    h("div", { class: "head" },
      h("h1", {}, "Shipments"),
      h("div", { class: "actions" }, h("a", { class: "button", href: "#/new" }, "New shipment"))),
    body);
}

function statusChip(s) {
  if (s.status === "approved") return chip("Approved", "ok");
  if (s.status === "declined") return chip("Declined", "high");
  return s.blocking_flags ? chip(s.blocking_flags + " to resolve", "warning") : chip("Draft");
}

// ---- new shipment ----------------------------------------------------------

async function showNew() {
  const config = await api("GET", "/api/config");
  let files = [];
  const list = h("ul", { class: "files" });
  const picker = h("input", { type: "file", multiple: true, accept: ".pdf,.png,.jpg,.jpeg,.webp", onchange: () => add(picker.files) });
  const drop = h("label", { class: "drop" }, picker,
    h("div", {}, "Drop the invoice and packing list here, or click to choose."),
    h("div", { class: "muted" }, "PDF, PNG or JPEG, up to 20 MB each."), list);
  const representation = h("select", {},
    h("option", { value: "direct" }, "Direct representation"),
    h("option", { value: "indirect" }, "Indirect representation"));
  const submit = h("button", { class: "primary", disabled: true, onclick: send }, "Read documents");

  function add(fileList) {
    files = files.concat(Array.from(fileList));
    list.replaceChildren(...files.map((f) => h("li", {}, f.name)));
    submit.disabled = !files.length || !config.reading_configured;
  }
  drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
  drop.addEventListener("dragleave", () => drop.classList.remove("over"));
  drop.addEventListener("drop", (e) => { e.preventDefault(); drop.classList.remove("over"); add(e.dataTransfer.files); });

  async function send() {
    await run(async () => {
      const form = new FormData();
      form.append("actor", actor());
      form.append("representation", representation.value);
      files.forEach((f) => form.append("files", f));
      submit.disabled = true;
      submit.textContent = "Reading… this can take a minute";
      try {
        const view = await api("POST", "/api/shipments", form);
        location.hash = "#/s/" + view.shipment.id;
      } finally {
        submit.disabled = false;
        submit.textContent = "Read documents";
      }
    });
  }

  async function sample() {
    const view = await run(() => api("POST", "/api/sample", { actor: actor() }));
    if (view) location.hash = "#/s/" + view.shipment.id;
  }

  app.replaceChildren(
    h("div", { class: "head" }, h("h1", {}, "New shipment")),
    h("div", { class: "card" },
      !config.reading_configured && h("div", { class: "notice" },
        "Document reading is not configured on this server, so uploads are switched off. You can still load the sample shipment to see how review works."),
      drop,
      h("div", { class: "fields" }, h("label", { class: "field" }, h("span", {}, "Acting as"), representation)),
      h("div", { class: "actions", style: "margin-top:16px" }, submit,
        h("button", { onclick: sample }, "Load sample shipment"))));
}

// ---- shipment --------------------------------------------------------------

async function showShipment(id) {
  render(await api("GET", "/api/shipments/" + id));
}

function render(view) {
  const s = view.shipment;
  const d = s.declaration;
  const locked = s.status !== "draft";
  const base = "/api/shipments/" + s.id;

  const act = async (method, path, body) => {
    const next = await run(() => api(method, base + path, Object.assign({ actor: actor() }, body)));
    if (next) render(next);
  };

  // An input that saves on change.
  function field(label, value, patch, options) {
    const opts = options || {};
    let input;
    if (opts.choices) {
      input = h("select", { disabled: locked }, Object.entries(opts.choices).map(([v, t]) => h("option", { value: v, selected: v === value }, t)));
    } else {
      input = h("input", { type: opts.number ? "number" : "text", step: "any", value: value === null || value === undefined ? "" : value, readOnly: locked, placeholder: locked ? "—" : opts.placeholder || "" });
    }
    input.addEventListener("change", () => {
      let v = input.value.trim();
      if (opts.number) v = v === "" ? null : Number(v);
      act("PATCH", "", patch(v));
    });
    return label === null ? input : h("label", { class: "field" }, h("span", {}, label), input);
  }
  const fixed = (label, value) => h("div", { class: "field static" }, h("span", {}, label), h("div", {}, value || "—"));

  // -- gate
  const openFlags = view.blocking_flags;
  const unconfirmed = view.unconfirmed_items.length;
  let gate;
  if (s.status === "approved") gate = h("div", { class: "gate approved" }, "Approved by " + s.approved_by + " — the declaration is locked.");
  else if (s.status === "declined") gate = h("div", { class: "gate declined" }, "Job declined: " + s.decline_reason);
  else if (!openFlags && !unconfirmed) gate = h("div", { class: "gate ready" }, "Everything is resolved and confirmed. Ready for your approval.");
  else gate = h("div", { class: "gate" }, "Before you can approve: " + [
    openFlags && openFlags + (openFlags === 1 ? " flag needs" : " flags need") + " a recorded resolution",
    unconfirmed && unconfirmed + (unconfirmed === 1 ? " commodity code needs" : " commodity codes need") + " confirming",
  ].filter(Boolean).join(" · "));

  // -- parties and terms
  const terms = h("div", { class: "card" },
    h("h2", {}, "Parties and terms"),
    h("div", { class: "fields" },
      fixed("Importer", d.importer.name),
      field("Importer VAT number", d.importer.vat_number, (v) => ({ importer: { vat_number: v } })),
      field("Importer EORI", d.importer.eori, (v) => ({ importer: { eori: v } }), { placeholder: "GR…" }),
      fixed("Exporter", [d.exporter.name, d.exporter.country].filter(Boolean).join(", ")),
      fixed("Invoice", [d.invoice_number, d.invoice_date].filter(Boolean).join(" · ")),
      fixed("Invoice total", money(d.invoice_total) + " " + d.currency),
      field("Currency", d.currency, (v) => ({ header: { currency: v } })),
      field("Exchange rate (" + (d.currency || "currency") + " per €1)", d.exchange_rate, (v) => ({ header: { exchange_rate: v } }), { number: true, placeholder: d.currency === "EUR" ? "not needed" : "required" }),
      field("Delivery terms", d.incoterm, (v) => ({ header: { incoterm: v } })),
      field("Freight (" + (d.currency || "invoice currency") + ")", d.freight, (v) => ({ header: { freight: v } }), { number: true }),
      field("Insurance (" + (d.currency || "invoice currency") + ")", d.insurance, (v) => ({ header: { insurance: v } }), { number: true }),
      field("Acting as", d.representation, (v) => ({ header: { representation: v } }), { choices: { direct: "Direct representative", indirect: "Indirect representative" } })));

  // -- items
  const lowItems = new Set(s.flags.filter((f) => f.check.startsWith("value_below")).map((f) => f.item_index));
  const itemRows = d.items.map((it, i) => {
    const perKg = it.customs_value_eur && it.net_kg ? it.customs_value_eur / it.net_kg : null;
    const patch = (name) => (v) => ({ items: { [i]: { [name]: v } } });
    const confidence = it.code_confidence && chip(it.code_confidence + " confidence", it.code_confidence === "low" ? "high" : it.code_confidence === "medium" ? "warning" : "ok");
    return h("tr", {},
      h("td", {}, i + 1),
      h("td", {}, it.description,
        h("div", { class: "muted" }, it.quantity.toLocaleString("en-GB") + " " + it.unit + " · " + it.packages + " " + it.package_type.toLowerCase())),
      h("td", { class: "code" },
        field(null, it.commodity_code, patch("commodity_code")),
        h("div", { style: "margin-top:6px; display:flex; gap:6px; flex-wrap:wrap; align-items:center" },
          it.code_confirmed ? chip("Confirmed", "ok")
            : !locked && h("button", { class: "small", disabled: it.commodity_code.length !== 10, onclick: () => act("POST", "/items/" + i + "/confirm") }, "Confirm code"),
          confidence),
        h("div", { class: "reason" },
          h("strong", {}, (CODE_SOURCES[it.code_source] || "No code yet") + ". "), it.code_reasoning,
          it.code_alternatives.length ? " Alternatives: " + it.code_alternatives.join(", ") + "." : "")),
      h("td", { class: "origin" }, field(null, it.origin_country, patch("origin_country"))),
      h("td", { class: "num weight" }, field(null, it.net_kg, patch("net_kg"), { number: true })),
      h("td", { class: "num" }, money(it.invoice_value)),
      h("td", { class: "num" }, money(it.customs_value_eur)),
      h("td", { class: "num " + (lowItems.has(i) ? "below" : "") }, perKg === null ? "—" : money(perKg)));
  });
  const items = h("div", { class: "card scroll" },
    h("h2", {}, "Items"),
    h("table", {},
      h("thead", {}, h("tr", {},
        h("th", {}, "#"), h("th", {}, "Goods"), h("th", {}, "Commodity code"), h("th", {}, "Origin"),
        h("th", { class: "num" }, "Net kg"), h("th", { class: "num" }, "Invoice " + d.currency),
        h("th", { class: "num" }, "Customs value €"), h("th", { class: "num" }, "€ per kg"))),
      h("tbody", {}, itemRows)));

  // -- flags
  const order = { high: 0, warning: 1, info: 2 };
  const sorted = s.flags.slice().sort((a, b) => (!!a.resolution - !!b.resolution) || order[a.severity] - order[b.severity]);
  const flagEls = sorted.map((f) => {
    const where = f.item_index === null ? "" : "Item " + (f.item_index + 1);
    const label = f.severity === "high" ? "High" : f.severity === "warning" ? "To resolve" : "Note";
    let footer = null;
    if (f.resolution) {
      footer = h("div", { class: "done" }, chip("Resolved", "ok"), " ", RESOLUTIONS[f.resolution] + " — " + f.resolution_note, h("span", { class: "muted" }, " (" + f.resolved_by + ")"));
    } else if (f.severity !== "info" && !locked) {
      const choice = h("select", { "aria-label": "How it was dealt with" }, Object.entries(RESOLUTIONS).map(([v, t]) => h("option", { value: v }, t)));
      const note = h("textarea", { placeholder: "What you checked or received, e.g. “Importer sent bank transfer of 12 Oct matching the invoice.”", "aria-label": "Note" });
      footer = h("form", { onsubmit: (e) => { e.preventDefault(); act("POST", "/flags/" + encodeURIComponent(f.id) + "/resolve", { resolution: choice.value, note: note.value }); } },
        choice, note, h("button", {}, "Record"));
    }
    return h("div", { class: "flag" },
      h("div", { class: "title" }, chip(label, f.resolution ? "" : f.severity), f.title, where && h("span", { class: "muted" }, where)),
      h("p", { class: "detail" }, f.detail),
      footer);
  });
  const flags = h("div", { class: "card" },
    h("h2", {}, "Checks"),
    flagEls.length ? flagEls : h("p", { class: "muted" }, "No flags were raised."));

  // -- record
  const describe = (e) => {
    const p = e.payload;
    switch (e.type) {
      case "field_changed": return p.field + ": " + (p.old === null || p.old === "" ? "empty" : p.old) + " → " + (p.new === null || p.new === "" ? "empty" : p.new);
      case "flag_raised": return p.title;
      case "flag_cleared": return p.title;
      case "flag_resolved": return p.title + " — " + (RESOLUTIONS[p.resolution] || p.resolution) + ": " + p.note;
      case "code_proposed": return "Item " + (p.item + 1) + ": " + p.code + " (" + (CODE_SOURCES[p.source] || p.source).toLowerCase() + ")";
      case "code_confirmed": return "Item " + (p.item + 1) + ": " + p.code;
      case "document_received": return p.filename + " · sha256 " + p.sha256.slice(0, 12) + "…";
      case "documents_read": return "Invoice " + p.invoice_number + ", " + p.lines + " lines, " + money(p.declared_total) + " " + p.currency;
      case "declaration_approved": return p.items + " items, customs value € " + money(p.customs_value_eur);
      case "job_declined": return p.reason;
      case "shipment_opened": return "Acting as " + p.representation + " representative";
      default: return "";
    }
  };
  const record = h("div", { class: "card" },
    h("h2", {}, "Record"),
    h("p", { class: "muted" }, view.record_intact
      ? "Every action on this shipment is logged below. The log verifies: no entry has been altered or removed."
      : "Warning: the log does not verify. An entry has been altered or removed."),
    h("ul", { class: "timeline" }, view.events.map((e) =>
      h("li", {}, h("span", { class: "muted" }, when(e.at)), h("span", {}, EVENTS[e.type] || e.type, h("div", { class: "muted" }, e.actor)), h("span", {}, describe(e))))));

  // -- header
  const decline = () => {
    const reason = window.prompt("Reason for declining this job (recorded in the file):");
    if (reason !== null) act("POST", "/decline", { reason });
  };
  const actions = h("div", { class: "actions" },
    h("a", { class: "button", href: base + "/record.pdf" }, "Download record (PDF)"),
    s.status === "approved" && h("a", { class: "button", href: base + "/declaration.json" }, "Export declaration"),
    !locked && h("button", { class: "danger", onclick: decline }, "Decline job"),
    !locked && h("button", { class: "primary", disabled: !!(openFlags || unconfirmed), onclick: () => act("POST", "/approve") }, "Approve declaration"));

  const focused = document.activeElement && document.activeElement.closest("main") ? window.scrollY : null;
  app.replaceChildren(
    h("div", { class: "head" },
      h("div", {},
        h("h1", {}, (d.importer.name || "Unknown importer") + " ← " + (d.exporter.name || "unknown exporter")),
        h("div", { class: "sub" }, "Invoice " + (d.invoice_number || "—") + " · opened " + when(s.created_at))),
      actions),
    gate, terms, items, flags, record);
  if (focused !== null) window.scrollTo(0, focused);
}

// ---- routing ---------------------------------------------------------------

async function route() {
  const hash = location.hash || "#/";
  const match = hash.match(/^#\/s\/([\w-]+)$/);
  try {
    if (match) await showShipment(match[1]);
    else if (hash === "#/new") await showNew();
    else await showList();
  } catch (error) {
    app.replaceChildren(h("div", { class: "card empty" }, error.message));
  }
}

try { actorInput.value = localStorage.getItem("declarant") || ""; } catch (_) {}
actorInput.addEventListener("change", () => { try { localStorage.setItem("declarant", actorInput.value.trim()); } catch (_) {} });
window.addEventListener("hashchange", route);
route();
