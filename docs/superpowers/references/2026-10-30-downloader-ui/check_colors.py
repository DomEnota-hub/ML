from pathlib import Path
from playwright.sync_api import sync_playwright
import base64
root=Path(__file__).resolve().parent/'screens'
root.mkdir(exist_ok=True)
src=Path(__file__).resolve().parent/'index.html'
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
 page=b.new_page(viewport={'width':1100,'height':1100});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.set_content(src.read_text());phone=page.locator('#phone')
 assert page.locator('#app h2').inner_text()=='Инструменты'
 colored=page.locator('.tool-symbol').first.evaluate('(e)=>getComputedStyle(e).color')
 phone.screenshot(path=str(root/'07-tools-color-dark.png'))
 page.get_by_role('button',name='Одноцветные',exact=True).click()
 mono=page.locator('.tool-symbol').first.evaluate('(e)=>getComputedStyle(e).color')
 assert colored!=mono
 assert page.get_by_role('button',name='Одноцветные',exact=True).get_attribute('aria-pressed')=='true'
 phone.screenshot(path=str(root/'11-tools-mono.png'))
 page.get_by_role('button',name='Цветные',exact=True).click()
 page.evaluate("go('settings')")
 assert page.locator('.setting .orb[data-tone=blue]').count()>=1
 phone.screenshot(path=str(root/'08-settings-color-dark.png'))
 page.evaluate('toggleTheme()');phone.screenshot(path=str(root/'10-settings-color-light.png'))
 page.evaluate("go('tools')")
 light=page.locator('.tool-symbol').first.evaluate('(e)=>getComputedStyle(e).color')
 assert light!=colored
 phone.screenshot(path=str(root/'09-tools-color-light.png'))
 page.set_viewport_size({'width':360,'height':800})
 for route in ['tools','settings']:
  page.evaluate('(route)=>go(route)',route)
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 assert not errors,errors
 print('Color toggle, both themes and 360px layouts: PASS. JS errors:',errors)
 for theme,names in [('dark',[('07-tools-color-dark.png','Инструменты'),('08-settings-color-dark.png','Настройки')]),('light',[('09-tools-color-light.png','Инструменты'),('10-settings-color-light.png','Настройки')])]:
  html='<html lang="ru"><meta charset="utf-8"><style>body{margin:0;padding:25px;background:#16171d;color:#efeaf6;font:14px system-ui}h1{font-size:21px;font-weight:550;margin:0 0 23px}.board{display:flex;gap:24px}section{width:390px}h2{font-size:12px;font-weight:500;color:#bcabd6;margin:0 0 12px}img{width:390px;display:block}</style><h1>Цветные иконки · '+('Тёмная тема' if theme=='dark' else 'Светлая тема')+'</h1><div class="board">'
  for file,label in names:html+='<section><h2>'+label+'</h2><img src="data:image/png;base64,'+base64.b64encode((root/file).read_bytes()).decode()+'"></section>'
  html+='</div></html>'
  page.set_viewport_size({'width':854,'height':980});page.set_content(html);page.screenshot(path=str(root/f'colored-icons-{theme}.png'),full_page=True)
 b.close()
