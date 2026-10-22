"""Generates the RS-02 field-recall corpus: 24 fictional campus resumes in varied real-world layouts.

Every person, school, company and number is invented. Output (default ./out): <id>.(docx|pdf|txt) plus
truth.json with the facts each file states. PDFs are converted from DOCX with LibreOffice, so
two-column table layouts exercise the same text-extraction order problems real PDFs have.

    python3 generate.py [out_dir]
"""
import argparse, hashlib, io, json, random, subprocess, sys, uuid, zipfile
from pathlib import Path
from docx import Document
from docx.shared import Pt
from docx.oxml.ns import qn

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('out_dir', nargs='?', default=str(Path(__file__).parent / 'out'))
parser.add_argument('--image-variants', action='store_true', help='also generate raster PNG/JPG/scanned PDF variants; requires Pillow and pdftoppm')
parser.add_argument('--pdf-engine', choices=['libreoffice', 'reportlab'], default='libreoffice')
args = parser.parse_args()
OUT = Path(args.out_dir)
OUT.mkdir(parents=True, exist_ok=True)
# Isolate LibreOffice's lock/profile from any interactive session; retain artifacts for inspection.
lo_profile = Path('/tmp') / ('offerpilot-recall-lo-' + uuid.uuid4().hex)
lo_profile.mkdir()
rnd = random.Random(1016)

SURNAMES = "林陈王赵周吴郑孙沈韩杨许何吕施张孔曹严华金魏陶姜"
GIVEN = ["知远", "若溪", "嘉树", "明哲", "思源", "雨桐", "子墨", "一航", "书瑶", "景行", "安然", "沐阳", "清和", "怀瑾", "星河", "语冰", "承泽", "以宁", "南乔", "亦舒"]
SCHOOLS = ["星海大学", "云岭理工大学", "东临师范大学", "北辰财经大学", "江屿科技大学", "西泽交通大学", "南澜大学", "青川工业大学"]
MAJORS = ["计算机科学与技术", "软件工程", "数据科学与大数据技术", "电子信息工程", "统计学", "金融学", "市场营销", "人工智能"]
CITIES = ["杭州", "上海", "北京", "深圳", "成都", "南京", "武汉", "西安"]
ORGS = [("蓝鲸云计算有限公司", "后端开发实习生"), ("拾光电商科技", "数据分析实习生"), ("远帆智能科技", "算法实习生"),
        ("青柚教育科技", "产品运营实习生"), ("北极星证券", "量化研究实习生"), ("微澜网络", "前端开发实习生"),
        ("橙果科技有限公司", "测试开发实习生"), ("海川咨询", "商业分析实习生")]
PROJECTS = [("校园二手交易平台", "后端负责人", ["Java", "Spring Boot", "MySQL", "Redis"]),
            ("基于BERT的评论情感分析", "算法开发", ["Python", "PyTorch", "Transformers"]),
            ("实验室设备预约小程序", "全栈开发", ["Vue", "Node.js", "MongoDB"]),
            ("城市共享单车需求预测", "数据建模", ["Python", "Pandas", "XGBoost"]),
            ("分布式日志检索系统", "核心开发", ["Go", "Kafka", "Elasticsearch"]),
            ("社团活动管理系统", "前端开发", ["React", "TypeScript", "Ant Design"])]
SKILLS = {"编程语言": ["Java", "Python", "Go", "C++", "SQL", "TypeScript"],
          "框架与工具": ["Spring Boot", "Docker", "Git", "Linux", "Redis", "Kafka", "Vue"],
          "数据分析": ["Pandas", "Excel", "Tableau", "SPSS"]}
CERTS = ["大学英语六级（CET-6）", "全国计算机等级考试二级", "软件设计师（中级）", "AWS Certified Cloud Practitioner", "证券从业资格", "普通话二级甲等"]
DATE_STYLES = [lambda y, m: f"{y}.{m:02d}", lambda y, m: f"{y}年{m}月", lambda y, m: f"{y}/{m:02d}",
               lambda y, m: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][m - 1] + f" {y}"]
AWARDS = ["校级一等奖学金", "全国大学生数学建模竞赛省级二等奖", "优秀学生干部", "蓝桥杯省赛三等奖", "国家励志奖学金"]
LAYOUTS = ["classic", "two_column", "pipe_header", "english_headings", "plain_text", "combined_sections"]
FORMATS = {"classic": "docx", "two_column": "pdf", "pipe_header": "pdf", "english_headings": "docx", "plain_text": "txt",
           "combined_sections": "pdf"}

def person(i):
    date = DATE_STYLES[i % 4]
    present = ["至今", "至今", "Present", "今"][i % 4]
    name = SURNAMES[i % len(SURNAMES)] + GIVEN[i % len(GIVEN)]
    phone = ["138", "159", "186", "177"][i % 4] + f"{rnd.randint(1000, 9999)}{rnd.randint(1000, 9999)}"
    shown_phone = [phone, f"+86 {phone[:3]}-{phone[3:7]}-{phone[7:]}", f"{phone[:3]} {phone[3:7]} {phone[7:]}", phone][i % 4]
    email = f"{['lin', 'chen', 'wang', 'zhao', 'zhou'][i % 5]}{i:02d}@example.com"
    city = CITIES[i % len(CITIES)]
    links = [f"https://github.com/demo-{i:02d}"] if i % 3 != 2 else []
    s1, s2 = rnd.sample(SCHOOLS, 2)
    major = MAJORS[i % len(MAJORS)]
    edu = [{"school": s1, "degree": "本科", "major": major, "start": date(2021 + i % 2, 9), "end": date(2025 + i % 2, 6),
            "gpa": ["3.8/4.0", "3.6/4.0", "专业前5%", "88/100"][i % 4]}]
    if i % 4 == 1:
        edu.insert(0, {"school": s2, "degree": "硕士", "major": major, "start": date(2025, 9), "end": date(2028, 6), "gpa": ""})
    exp = []
    for k, (org, title) in enumerate(rnd.sample(ORGS, 1 + i % 2)):
        end = present if k == 0 and i % 5 == 0 else date(2024, 9 + k)
        exp.append({"org": org, "title": title, "start": date(2024, 6 + k), "end": end,
                    "bullets": [f"负责{['接口开发', '数据清洗', '模型训练', '活动策划', '因子研究', '页面重构', '自动化测试', '行业研究'][(i + k) % 8]}，"
                                f"推动效率提升{10 + (i * 7 + k) % 40}%",
                                f"参与{['周报自动化', '用户画像', 'A/B 测试', '监控告警'][(i + k) % 4]}，覆盖{200 + i * 13}名用户"]})
    proj = []
    for name_, role, tech in rnd.sample(PROJECTS, 1 + (i + 1) % 2):
        proj.append({"name": name_, "role": role, "start": date(2023, 3), "end": date(2023, 6), "tech": tech,
                     "bullets": [f"设计并实现核心模块，日活{50 + i * 3}人", "编写单元测试，缺陷率下降30%"]})
    skills = [{"category": c, "items": rnd.sample(items, 3)} for c, items in rnd.sample(list(SKILLS.items()), 2)]
    certs = rnd.sample(CERTS, 1 + i % 3)
    summary = ["热爱后端开发，有较强的学习能力和团队协作意识。", "对数据敏感，善于用分析驱动决策。", "",
               "具备扎实的算法基础，乐于钻研新技术。"][i % 4]
    return {"id": f"r{i + 1:02d}", "layout": LAYOUTS[i % len(LAYOUTS)], "awards": rnd.sample(AWARDS, 2),
            "basics": {"name": name, "phone": phone, "shownPhone": shown_phone, "email": email, "city": city, "links": links},
            "education": edu, "experience": exp, "projects": proj, "skills": skills, "certificates": certs, "summary": summary}

CERT_LABELS = ["语言能力", "计算机", "职业资格"]

def combined_sections(p):
    """Common campus template: role on its own line, skills and certificates merged, an awards section."""
    out = [("教育经历", [f"{e['school']}｜{e['degree']}｜{e['major']}｜{e['start']}-{e['end']}" + (f"｜{e['gpa']}" if e['gpa'] else "") for e in p["education"]])]
    lines = []
    for e in p["experience"]:
        lines += [f"{e['start']}-{e['end']}  {e['org']}", f"职位：{e['title']}"] + [f"· {b}" for b in e["bullets"]]
    out.append(("实习经历", lines))
    lines = []
    for pr in p["projects"]:
        lines += [f"{pr['start']}-{pr['end']}  {pr['name']}", f"项目角色：{pr['role']}", "使用技术：" + "/".join(pr["tech"])] + [f"· {b}" for b in pr["bullets"]]
    out.append(("项目经历", lines))
    lines = [f"{s['category']}：{'、'.join(s['items'])}" for s in p["skills"]]
    lines += [f"{CERT_LABELS[k % 3]}：{c}" for k, c in enumerate(p["certificates"])]
    out.append(("技能证书", lines))
    out.append(("荣誉奖项", p["awards"]))
    if p["summary"]:
        out.append(("个人总结", [p["summary"]]))
    return out

def sections(p, english=False):
    if p["layout"] == "combined_sections":
        return combined_sections(p)
    h = (lambda zh, en: en) if english else (lambda zh, en: zh)
    out = []
    out.append((h("教育背景", "Education"), [f"{e['school']}  {e['major']}  {e['degree']}  {e['start']} - {e['end']}"
                                            + (f"  GPA：{e['gpa']}" if e['gpa'] and '/' in e['gpa'] else (f"  {e['gpa']}" if e['gpa'] else "")) for e in p["education"]]))
    lines = []
    for e in p["experience"]:
        lines.append(f"{e['org']}  |  {e['title']}  |  {e['start']} – {e['end']}")
        lines += [f"• {b}" for b in e["bullets"]]
    out.append((h("实习经历", "Experience"), lines))
    lines = []
    for pr in p["projects"]:
        lines.append(f"{pr['name']}（{pr['role']}）  {pr['start']} - {pr['end']}")
        lines.append("技术栈：" + "、".join(pr["tech"]))
        lines += [f"• {b}" for b in pr["bullets"]]
    out.append((h("项目经历", "Projects"), lines))
    out.append((h("专业技能", "Skills"), [f"{s['category']}：{'、'.join(s['items'])}" for s in p["skills"]]))
    out.append((h("证书", "Certificates"), ["、".join(p["certificates"])]))
    if p["summary"]:
        out.append((h("自我评价", "Summary"), [p["summary"]]))
    return out

def contact_lines(p):
    b = p["basics"]
    lines = [f"电话：{b['shownPhone']}", f"邮箱：{b['email']}", f"现居：{b['city']}"]
    return lines + [f"GitHub：{l}" for l in b["links"]]

def new_document():
    # Without an East Asian font hint LibreOffice renders some CJK runs in a Latin font and drops them from the PDF.
    d = Document()
    for style in ("Normal", "Title", "Heading 1"):
        font = d.styles[style].font
        font.name = "Noto Sans CJK SC"
        font.element.rPr.rFonts.set(qn("w:eastAsia"), "Noto Sans CJK SC")
    d.styles["Normal"].font.size = Pt(10.5)
    return d

def classic(p, english=False):
    d = new_document()
    d.add_heading(p["basics"]["name"], level=0)
    for line in contact_lines(p):
        d.add_paragraph(line)
    for title, lines in sections(p, english):
        d.add_heading(title, level=1)
        for line in lines:
            d.add_paragraph(line)
    return d

def two_column(p):
    d = new_document()
    d.add_heading(p["basics"]["name"], level=0)
    table = d.add_table(rows=1, cols=2)
    left, right = table.rows[0].cells
    left.width, right.width = Pt(150), Pt(330)
    secs = sections(p)
    left.paragraphs[0].add_run("联系方式").bold = True
    for line in contact_lines(p):
        left.add_paragraph(line)
    for title, lines in secs:
        cell = left if title in ("专业技能", "证书") else right
        cell.add_paragraph().add_run(title).bold = True
        for line in lines:
            cell.add_paragraph(line)
    return d

def pipe_header(p):
    d = new_document()
    b = p["basics"]
    d.add_heading(b["name"], level=0)
    d.add_paragraph("  |  ".join([b["shownPhone"], b["email"], b["city"]] + b["links"]))
    for title, lines in sections(p):
        d.add_paragraph().add_run(f"【{title}】").bold = True
        for line in lines:
            d.add_paragraph(line)
    return d

def plain_text(p):
    out = [p["basics"]["name"]] + contact_lines(p)
    for title, lines in sections(p):
        out += ["", title] + lines
    return "\n".join(out) + "\n"

truth = []
for i in range(24):
    p = person(i)
    fmt = FORMATS[p["layout"]]
    path = OUT / f"{p['id']}.{fmt}"
    if p["layout"] == "plain_text":
        path.write_text(plain_text(p), encoding="utf-8")
    else:
        doc = {"classic": classic, "two_column": two_column, "pipe_header": pipe_header, "combined_sections": classic,
               "english_headings": lambda q: classic(q, english=True)}[p["layout"]](p)
        docx = OUT / f"{p['id']}.docx"
        raw = io.BytesIO(); doc.save(raw)
        # ZIP timestamps would otherwise make identical synthetic sources hash differently on rerun.
        with zipfile.ZipFile(raw) as source, zipfile.ZipFile(docx, 'w', compression=zipfile.ZIP_DEFLATED) as target:
            for name in sorted(source.namelist()):
                entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(entry, source.read(name))
        if fmt == "pdf":
            if args.pdf_engine == 'reportlab':
                from render_pdf import render
                render(path, p, sections, contact_lines)
            else:
                subprocess.run(["soffice", "-env:UserInstallation=" + lo_profile.as_uri(), "--headless", "--convert-to", "pdf", "--outdir", str(OUT), str(docx)],
                               check=True, capture_output=True)
            # Keep intermediate DOCX files for audit/reproduction; never delete source artifacts.
    p["file"] = path.name
    if fmt == "pdf":
        # Every stated fact must survive into the PDF text layer, or the truth would be unfair.
        text = subprocess.run(["pdftotext", str(path), "-"], check=True, capture_output=True, text=True).stdout
        squash = lambda s: "".join(s.split())
        facts = [p["basics"]["name"], p["basics"]["email"]] + p["certificates"] + [x["name"] for x in p["projects"]]
        def present(fact):  # in order, allowing column interleaving at wrapped lines
            it = iter(squash(text))
            return all(ch in it for ch in squash(fact))
        lost = [f for f in facts if not present(f)]
        assert not lost, f"{path.name} lost {lost}"
    truth.append(p)
if args.image_variants:
    from PIL import Image, ImageOps
    # Derivatives never increase the number of independent people in the gate.
    for original in [p for p in truth if p['file'].endswith('.pdf')][:4]:
        prefix = OUT / (original['id'] + '-page')
        subprocess.run(['pdftoppm', '-png', '-scale-to', '1600', str(OUT / original['file']), str(prefix)], check=True, capture_output=True)
        pages = [Image.open(path).convert('RGB') for path in sorted(OUT.glob(prefix.name + '-*.png'))]
        canvas = Image.new('RGB', (max(page.width for page in pages), sum(page.height for page in pages)), 'white')
        y = 0
        for page in pages:
            canvas.paste(page, (0, y)); y += page.height
        variants = {
            'png': (canvas, {}),
            # Synthetic scan-like stress input, not an actual phone photograph.
            'jpg': (ImageOps.expand(canvas, border=24, fill='#dddddd').rotate(1.5, expand=True, fillcolor='white'), {'quality': 58}),
            'pdf': (pages[0], {'save_all': True, 'append_images': pages[1:], 'resolution': 100.0, 'creationDate': None, 'modDate': None}),
        }
        for fmt, (picture, options) in variants.items():
            sample = json.loads(json.dumps(original)); sample['sourceId'] = original['id']
            sample['id'] += '-' + ('scan' if fmt == 'pdf' else fmt)
            sample['file'] = sample['id'] + '.' + fmt
            sample['layout'] += '-synthetic-' + fmt
            picture.save(OUT / sample['file'], **options)
            truth.append(sample)
manifest = {'provenance': 'generated_synthetic', 'seed': 1016, 'pdfEngine': args.pdf_engine, 'independentResumes': 24,
            'samples': [{'id': p['id'], 'sourceId': p.get('sourceId', p['id']), 'file': p['file'],
                         'sha256': hashlib.sha256((OUT / p['file']).read_bytes()).hexdigest()} for p in truth]}
(OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
(OUT / "truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"wrote {len(truth)} resumes to {OUT}")
