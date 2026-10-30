from pathlib import Path
from playwright.sync_api import sync_playwright
src=Path(__file__).resolve().parent/'index.html'
output=src.parent/'screens'
output.mkdir(exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
 page=browser.new_page(viewport={'width':1100,'height':1100},device_scale_factor=1)
 errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.set_content(src.read_text());page.wait_for_timeout(150)
 phone=page.locator('#phone')
 phone.screenshot(path=str(output/'01-home.png'))
 page.locator('#url').fill('https://youtube.com/watch?v=demo')
 page.get_by_role('button',name='Найти файл',exact=True).click()
 assert page.locator('#app [role=radio][aria-checked=true]').count()==1
 page.get_by_role('button',name='Предпросмотр 720p',exact=True).click()
 assert page.locator('#app h2').inner_text()=='Предпросмотр'
 page.locator('#app').get_by_role('button',name='Назад',exact=True).click()
 page.locator('[role=radio]').filter(has_text='720p').click()
 assert '720p' in page.locator('[role=radio][aria-checked=true]').inner_text()
 phone.screenshot(path=str(output/'04-formats.png'))
 page.locator('#app').get_by_role('button',name='Скачать · 720p',exact=True).click()
 assert page.locator('#app').get_by_text('Большое путешествие · 720p',exact=True).count()==1
 page.evaluate("S.toast=null;renderToast()")
 phone.screenshot(path=str(output/'02-downloads.png'))
 page.locator('#app').get_by_role('button',name='Выбрать файлы',exact=True).click()
 page.locator('#app').get_by_role('button',name='Выбрать все',exact=True).click()
 assert page.locator('#app h2').inner_text()=='Выбрано: 4'
 page.locator('#app').get_by_role('button',name='Удалить выбранное',exact=True).click()
 assert page.get_by_role('dialog').count()==1
 page.get_by_role('button',name='Отмена',exact=True).click()
 page.locator('#app').get_by_role('button',name='Отменить выбор',exact=True).click()
 page.evaluate("go('tools')")
 phone.screenshot(path=str(output/'03-tools.png'))
 page.evaluate("go('settings')")
 phone.screenshot(path=str(output/'05-settings.png'))
 # Every page must render without script errors; check actual horizontal bounds of its main content.
 failures=[]
 for name in page.evaluate('Object.keys(pages)'):
  page.evaluate('(name)=>go(name)',name)
  if page.evaluate("document.querySelector('#app').scrollWidth>document.querySelector('#app').clientWidth+1"):
   failures.append(name)
 page.evaluate("go('home');toggleTheme()")
 phone.screenshot(path=str(output/'06-light.png'))
 page.set_viewport_size({'width':360,'height':800})
 page.evaluate("go('downloads')")
 assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 print('Scenarios passed. Page errors:',errors,'Horizontal overflow:',failures)
 assert not errors and not failures
 browser.close()
