from pathlib import Path
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox']);page=b.new_page(viewport={'width':1120,'height':1100});errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content((r/'MediaLoader_video_editor_reference_v3.html').read_text());ed=page.locator('#editor')
 page.evaluate("chooseSection('text')");page.locator('[data-action=add-text]').click()
 assert page.locator('.text-keyboard').count()==1
 ed.screenshot(path=str(r/'05-text-keyboard.png'))
 page.locator('#textInput').fill('Наш текст')
 page.locator('[data-text-tab=style]').click();assert page.locator('[data-text-style]').count()==6
 page.locator('[data-text-color="#ff2d55"]').click()
 page.locator('[data-text-style=bold]').click();page.locator('[data-text-style=outline]').click();page.locator('[data-text-style=shadow]').click()
 page.locator('[data-range=textOpacity]').evaluate('(e)=>{e.value=55;e.dispatchEvent(new Event("input",{bubbles:true}))}')
 assert page.evaluate("S.textBold&&S.textOutline&&S.textShadow&&S.textOpacity===55&&S.textColor==='#ff2d55'")
 page.locator('[data-text-style=left]').click();assert page.evaluate("S.textAlign==='left'")
 assert page.locator('#textContent').evaluate('(e)=>getComputedStyle(e).opacity')=='0.55'
 page.locator('[data-text-style=center]').click();ed.screenshot(path=str(r/'06-text-style.png'))
 page.locator('[data-text-tab=font]').click();assert page.locator('[data-text-font]').count()==1
 ed.screenshot(path=str(r/'07-text-font.png'))
 page.locator('[data-action=apply]:visible').click();assert page.evaluate('S.textLayers.length')==1
 page.locator('[data-action=add-text]').click();page.locator('#textInput').fill('Вторая надпись');page.locator('[data-action=apply]:visible').click()
 assert page.evaluate('S.textLayers.length')==2
 assert page.locator('#otherTextLayers [data-text-id]').count()==1
 box=page.locator('#textLayer').bounding_box();page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2);page.mouse.down();page.mouse.move(box['x']+box['width']/2,box['y']+box['height']/2-80,steps=5);page.mouse.up();page.locator('#otherTextLayers [data-text-id]').click();assert page.evaluate("S.text==='Наш текст'")
 page.evaluate("chooseSection('main');enter('frame')")
 with page.expect_download() as out:page.locator('[data-action=apply]:visible').click()
 out.value.save_as(str(r/'test-styled-frame.png'));assert (r/'test-styled-frame.png').stat().st_size>100
 # Match source default Style/Font screenshots without test edits.
 page.evaluate("S.textLayers=[];S.text='';S.textColor='#ffffff';S.textOpacity=100;S.textBold=false;S.textOutline=false;S.textShadow=false;S.textAlign='center';S.textRotation=0;S.section='text';S.textTab='style';enter('text')")
 ed.screenshot(path=str(r/'08-text-style-source.png'))
 page.locator('[data-text-tab=font]').click();ed.screenshot(path=str(r/'09-text-font-source.png'))
 page.set_viewport_size({'width':360,'height':800});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 assert not errors,errors
 print('Text keyboard/style/font; palette/opacity/bold/outline/shadow/alignment; multiple layers; styled PNG export; 360px: PASS. JS errors:',errors)
 b.close()
