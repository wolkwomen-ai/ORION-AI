"""Create and persist downloadable product files for ARION products."""
from __future__ import annotations

from io import BytesIO
import re
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from xml.sax.saxutils import escape


def _safe_name(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9_-]+", "_", value).strip("_")
    return (value[:70] or "ARION_Product")


def _make_pdf(title: str, content: str) -> bytes:
    buffer = BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=A4, rightMargin=20*mm, leftMargin=20*mm,
                            topMargin=18*mm, bottomMargin=18*mm, title=title,
                            author="ARION Digital Studio")
    styles = getSampleStyleSheet()
    heading = ParagraphStyle("ARIONTitle", parent=styles["Title"], alignment=TA_CENTER,
                             textColor=colors.HexColor("#17365D"), spaceAfter=16)
    body = ParagraphStyle("ARIONBody", parent=styles["BodyText"], leading=15,
                          spaceAfter=7, wordWrap="CJK")
    story = [Paragraph(escape(title), heading), Spacer(1, 4*mm)]
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line:
            story.append(Spacer(1, 3))
        elif len(line) < 100 and (line.isupper() or line.startswith(("## ", "# "))):
            story.append(Paragraph(escape(line.lstrip("# ")), styles["Heading2"]))
        else:
            story.append(Paragraph(escape(line).replace("\n", "<br/>"), body))
    doc.build(story)
    return buffer.getvalue()


def build_product_files(conn, product_id: str, title: str, content: str) -> None:
    """Persist both downloadable formats in SQLite, in the same product-creation cycle."""
    base = _safe_name(title)
    pdf = _make_pdf(title, content)
    txt = (title + "\n" + "=" * min(len(title), 72) + "\n\n" + content + "\n").encode("utf-8")
    from datetime import datetime, timezone
    created = datetime.now(timezone.utc).isoformat()
    conn.execute("INSERT OR REPLACE INTO product_files VALUES(?,?,?,?,?,?)",
                 (product_id, "pdf", base + ".pdf", "application/pdf", pdf, created))
    conn.execute("INSERT OR REPLACE INTO product_files VALUES(?,?,?,?,?,?)",
                 (product_id, "txt", base + ".txt", "text/plain; charset=utf-8", txt, created))
