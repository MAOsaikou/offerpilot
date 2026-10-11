#!/usr/bin/env node
// No new dependencies: minimal Markdown subset + installed Chrome/Playwright.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
const here=path.dirname(new URL(import.meta.url).pathname);
const source=path.join(here,'OfferPilot-技术文档.md');
const out=path.join(here,'OfferPilot-技术文档.pdf');
const escape=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
function evidenceUrl(url){
 if(url.startsWith('http'))return url;
 const relative=path.relative(path.resolve(here,'../..'),path.resolve(here,url)).replaceAll('\\','/');
 const ref=relative.startsWith('docs/submission/assets/')?'docs/tech-doc-draft':'7054a54a64c5f2b9e27194d82807e3dc239e0586';
 return 'https://github.com/MAOsaikou/offerpilot/blob/'+ref+'/'+relative;
}
function inline(s){
 const codes=[];s=s.replace(/`([^`]+)`/g,(_,v)=>{codes.push('<code>'+escape(v)+'</code>');return '\u0000'+(codes.length-1)+'\u0000';});
 s=escape(s).replace(/\[([^\]]+)\]\(([^)]+)\)/g,(_,label,url)=>`<a href="${evidenceUrl(url)}">${label}</a>`).replace(/\*\*([^*]+)\*\*/g,'<strong>$1</strong>');
 return s.replace(/\u0000(\d+)\u0000/g,(_,n)=>codes[n]);
}
function markdown(text){
 const lines=text.trim().split('\n'),html=[];let i=0;
 while(i<lines.length){const line=lines[i];
  if(!line.trim()){i++;continue;}
  if(line.startsWith('```')){const code=[];i++;while(i<lines.length&&!lines[i].startsWith('```'))code.push(lines[i++]);i++;html.push('<pre><code>'+escape(code.join('\n'))+'</code></pre>');continue;}
  if(line.startsWith('<figure')){html.push(line.replace(/src="assets\//g,`src="${pathToFileURL(here+'/assets/').href}`));i++;continue;}
  const heading=line.match(/^(#{1,6}) (.*)$/);if(heading){const id='h-'+html.length+'-'+escape(heading[2]).replace(/\s/g,'-');html.push(`<h${heading[1].length} id="${id}">${inline(heading[2])}</h${heading[1].length}>`);i++;continue;}
  if(line.startsWith('|')){const rows=[];while(i<lines.length&&lines[i].startsWith('|'))rows.push(lines[i++].split('|').slice(1,-1).map(x=>x.trim()));const data=rows.filter(r=>!r.every(c=>/^:?-+:?$/.test(c)));html.push('<table><thead><tr>'+data[0].map(c=>'<th>'+inline(c)+'</th>').join('')+'</tr></thead><tbody>'+data.slice(1).map(r=>'<tr>'+r.map(c=>'<td>'+inline(c)+'</td>').join('')+'</tr>').join('')+'</tbody></table>');continue;}
  if(line.startsWith('- ')){const list=[];while(i<lines.length&&lines[i].startsWith('- '))list.push(lines[i++].slice(2));html.push('<ul>'+list.map(x=>'<li>'+inline(x)+'</li>').join('')+'</ul>');continue;}
  const para=[];while(i<lines.length&&lines[i].trim()&&!/^(#{1,6} |\||<figure|```|- )/.test(lines[i]))para.push(lines[i++]);html.push('<p>'+inline(para.join(' '))+'</p>');
 }
 return html.join('\n');
}
const parts=fs.readFileSync(source,'utf8').split(/<!-- page: \d+ -->/).filter(x=>x.trim());
if(parts.length!==30)throw Error('Expected 30 editorial pages');
const style=`@page{size:A4;margin:20mm}*{box-sizing:border-box}body{font-family:'Noto Sans CJK SC',sans-serif;font-size:11pt;line-height:1.55;color:#182334;margin:0}section.page{height:254mm;display:flow-root;padding-top:0.1mm;break-after:page;position:relative}section.page:last-child{break-after:auto}h1{font-size:19pt;line-height:1.3;margin:0 0 5mm;color:#182334}h2{font-size:13.5pt;line-height:1.4;margin:4mm 0 3mm}h3{font-size:11.5pt;line-height:1.4;margin:4mm 0 2mm}p{margin:0 0 3mm;text-align:justify;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:10.5pt;margin:3mm 0 4mm}th,td{border:0.2mm solid #b9c3ce;padding:1.8mm 2mm;text-align:left;vertical-align:top;overflow-wrap:anywhere}th{background:#edf1f5;font-weight:600}a{color:#263f66;text-decoration:none}code{font-family:'Noto Sans CJK SC',sans-serif;font-size:10.5pt;overflow-wrap:anywhere}pre{white-space:pre-wrap;padding:2mm 3mm;background:#f1f4f7;line-height:1.35;margin:3mm 0}figure{margin:3mm 0 4mm;text-align:center;break-inside:avoid}figure img{width:100%;max-height:83mm;object-fit:contain}figcaption{font-size:9pt;line-height:1.4;text-align:left;color:#435268;margin-top:1.5mm}.shot img{max-height:68mm}.compact img{max-height:48mm}.page[data-page="20"] .compact img{max-height:68mm}.page[data-page="15"] .compact img{max-height:60mm}.pdf-shot img{max-height:52mm}.terminal img{max-height:84mm}.page:first-child h1{font-size:28pt;margin-top:4mm}.page:first-child h2{font-size:17pt}.page:first-child table{font-size:10.5pt}.page:first-child p{font-size:10.5pt}.page:first-child{padding-top:2mm}`;
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'offerpilot-pdf-'));
const htmlFile=path.join(temp,'document.html');fs.writeFileSync(htmlFile,`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>OfferPilot（职航）技术文档 · 第一稿</title><style>${style}</style><body>${parts.map((x,i)=>`<section class="page" data-page="${i+1}">${markdown(x)}</section>`).join('')}</body></html>`);
let pw;for(const candidate of [process.env.PLAYWRIGHT_MODULE,path.join(here,'../../frontend/node_modules/playwright-core'),'/workspace/smoke1011/ui/node_modules/playwright-core']){if(!candidate)continue;try{pw=createRequire(import.meta.url)(candidate);break;}catch{}}
if(!pw)throw Error('Set PLAYWRIGHT_MODULE to an existing playwright-core installation');
const browser=await pw.chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true});
try{
 const page=await browser.newPage();await page.goto(pathToFileURL(htmlFile).href);await page.emulateMedia({media:'print'});await page.evaluate(()=>document.fonts.ready);
 await page.waitForFunction(()=>Array.from(document.images).every(x=>x.complete&&x.naturalWidth>0),{},{timeout:20000});
 const overflow=await page.evaluate(()=>Array.from(document.querySelectorAll('section.page')).map(s=>({page:Number(s.dataset.page),bottom:Math.max(...Array.from(s.children).map(c=>c.getBoundingClientRect().bottom))-s.getBoundingClientRect().top,height:s.getBoundingClientRect().height})).filter(x=>x.bottom>x.height+1));
 if(overflow.length)throw Error('Content exceeds A4 page: '+JSON.stringify(overflow));
 await page.pdf({path:out,format:'A4',preferCSSPageSize:true,printBackground:true,displayHeaderFooter:true,headerTemplate:'<span></span>',footerTemplate:'<div style="width:100%;text-align:center;font-size:9px;font-family:sans-serif;color:#536174">OfferPilot · <span class="pageNumber"></span> / <span class="totalPages"></span></div>',outline:true,tagged:true});
 console.log(JSON.stringify({output:out,editorialPages:parts.length,htmlPreview:htmlFile}));
}finally{await browser.close();}
