from pathlib import Path
import re,json
root=Path(__file__).resolve().parent
js=(root/'editor.js').read_text();css=(root/'editor.css').read_text();assets=json.loads((root/'assets.json').read_text())
paths=dict(re.findall(r"(\w+):'([^']*)'",js[js.index('const paths='):js.index('const modifiers=')]))
html=(root/'index.template.html').read_text().replace('@@CSS@@',css).replace('@@JS@@',js).replace('@@ASSETS@@','const ASSETS='+json.dumps(assets)+';')
for key in ['close','more','play','undo','redo','check','sound']:
 html=html.replace('@@'+key.upper()+'@@',f'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="{paths[key]}"/></svg>')
assert not re.search(r'@@\w+@@',html)
(root/'MediaLoader_video_editor_reference_v2.html').write_text(html)
print('Built standalone HTML:',len(html.encode()),'bytes')
