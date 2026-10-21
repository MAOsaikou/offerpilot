"""Optional offline PDF renderer for environments where LibreOffice cannot start.

Uses ReportLab's standard CJK CID font; source text and layout are generated from the same facts.
This is an explicitly separate renderer, not an equivalent LibreOffice conversion benchmark.
"""
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont


def render(path, person, sections, contacts):
    pdfmetrics.registerFont(UnicodeCIDFont('STSong-Light'))
    pdf = canvas.Canvas(str(path), pagesize=(595, 842), invariant=1)
    def lines(text, width):
        current = ''
        for ch in text:
            if current and pdfmetrics.stringWidth(current + ch, 'STSong-Light', 10) > width:
                yield current
                current = ''
            current += ch
        if current:
            yield current
    def column(entries, x, y, width):
        for entry in entries:
            for line in lines(entry, width):
                if y < 35:
                    pdf.showPage(); y = 805
                pdf.setFont('STSong-Light', 10)
                pdf.drawString(x, y, line); y -= 15
        return y
    column([person['basics']['name']], 35, 805, 520)
    data = sections(person)
    if person['layout'] == 'two_column':
        left = contacts(person)
        right = []
        for heading, items in data:
            target = left if heading in ('专业技能', '证书') else right
            target += ['', heading] + items
        column(left, 35, 775, 180)
        column(right, 235, 775, 325)
    else:
        entries = contacts(person)
        for heading, items in data:
            entries += ['', heading] + items
        column(entries, 35, 775, 520)
    pdf.save()
