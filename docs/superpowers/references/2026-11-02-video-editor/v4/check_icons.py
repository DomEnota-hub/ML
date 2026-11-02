from pathlib import Path
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
 for version in [3,4]:
  page=b.new_page(viewport={'width':1120,'height':1060},device_scale_factor=2);errors=[]
  page.on('pageerror',lambda e:errors.append(str(e)))
  page.set_content(((r.parent/'v3' if version==3 else r)/f'MediaLoader_video_editor_reference_v{version}.html').read_text())
  page.locator('#editor').screenshot(path=str(r/f'icons-v{version}-clip.png'))
  page.evaluate("chooseSection('text')");page.locator('[data-action=add-text]').click();page.locator('[data-text-tab=style]').click()
  page.locator('#editor').screenshot(path=str(r/f'icons-v{version}-style.png'))
  if version==4:
   assert page.locator('[data-text-style] svg').count()==6
   page.locator('#textInput').fill('Текст')
   for key in ['bold','outline','shadow','left']:page.locator(f'[data-text-style={key}]').click()
   assert page.evaluate("S.textBold&&S.textOutline&&S.textShadow&&S.textAlign==='left'")
   page.set_viewport_size({'width':360,'height':800})
   assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
   page.locator('#editor').screenshot(path=str(r/'icons-v4-mobile.png'))
  assert not errors,errors
  page.close()
 b.close()
print('Icon panels render; all six style controls and toggles work; 360px fits; no JS errors. PASS')
