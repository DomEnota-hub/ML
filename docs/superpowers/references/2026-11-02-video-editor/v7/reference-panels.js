/* v6: panels grounded in Образцы / Основное меню and its six section folders. */
Object.assign(S,{section:'main',selected:null,time:0,projectRatio:'Исходное',musicClips:[],musicSelected:null});
S.clips[0].duration=S.clips[0].end=7.2;
const ratios=[['Исходное',0],['1:1',1],['3:4',.75],['4:3',4/3],['16:9',16/9],['9:16',9/16],['2.39:1',2.39],['2.55:1',2.55]];
Object.assign(paths,{musicImport:'M3 6h7l2 2h9v12H3zM12 11v6m-3-3h6',musicEdit:'M13 4H4v17h17v-9M10 14l2-5 7-7 3 3-7 7z'});
const musicLibrary=[];let musicPreview=new Audio(),previewTrackId=null,pickerTab='device';
const priorSnapshot=snapshot;snapshot=function(){return{...priorSnapshot(),projectRatio:S.projectRatio,musicClips:JSON.parse(JSON.stringify(S.musicClips)),musicSelected:S.musicSelected}};
const priorUpdatePreview=updatePreview;updatePreview=function(){
 const preview=$('preview'),device=$('editor'),ratio=ratios.find(r=>r[0]===S.projectRatio)?.[1]||(currentClip()?.url&&$('video').videoWidth?$('video').videoWidth/$('video').videoHeight:9/16);
 if(S.mode!=='crop'){
  const w=Math.min(device.clientWidth*.88,device.clientHeight*.4023*ratio),h=w/ratio;
  preview.style.left=(device.clientWidth-w)/2+'px';preview.style.width=w+'px';preview.style.height=h+'px';preview.style.top=(device.clientHeight*.1105+(device.clientHeight*.4023-h)/2)+'px';
 }else for(const key of ['left','width','height','top'])preview.style[key]='';
 priorUpdatePreview();preview.classList.toggle('aspect-selected',S.mode==='aspect');
};
function projectRatioIcon(r){if(r===0)return svg('aspect');const w=r>=1?18:18*r,h=r>=1?18/r:18;return `<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="${12-w/2}" y="${12-h/2}" width="${w}" height="${h}" rx="2"/></svg>`}
const priorRenderPanel=renderPanel;renderPanel=function(){
 if(S.mode==='aspect'){
  $('panel').className='panel aspect-panel';$('panel').innerHTML=`<div class="cards-row project-ratios">${ratios.map(([name,r])=>`<button class="ratio-tile ${S.projectRatio===name?'active':''}" data-project-ratio="${name}" aria-pressed="${S.projectRatio===name}">${projectRatioIcon(r)}<span>${name}</span></button>`).join('')}</div>`;$('panelTitle').textContent='Соотношение сторон';return;
 }
 if(S.mode==='music-volume'){
  const c=selectedMusic();$('panel').className='panel volume-panel';$('panel').innerHTML=slider('musicVolume',c?.volume??100,0,100)+`<div class="volume-mark">${c?.volume??100}%</div>`;$('panelTitle').textContent='Громкость';return;
 }
 priorRenderPanel();
};
const priorRenderDock=renderDock;renderDock=function(){
 if(S.section==='extras'||S.section==='music-clip'){
  $('dock').classList.remove('root-dock');$('dock').innerHTML=`<div class="dock-back"><button data-action="root" aria-label="Все инструменты">${svg('back')}</button></div><div class="dock-scroll">${S.section==='extras'?tool('camera','Сохранить кадр','frame')+tool('audioSave','Извлечь аудио','audio-save'):tool('musicEdit','Изменить','music-edit')+tool('sound','Громкость','music-volume')+tool('trim','Обрезка','music-split')+tool('copy','Копировать','music-copy')+tool('trash','Удалить','music-delete')}</div>`;return;
 }
 priorRenderDock();
};
function selectedMusic(){return S.musicClips.find(c=>c.id===S.musicSelected)||S.musicClips[0]}
function musicTrack(id){return musicLibrary.find(t=>t.id===id)}
function musicDuration(c){return Math.max(0,c.sourceEnd-c.sourceStart)}
function waveform(t){const n=100;return `<svg class="waveform" viewBox="0 0 ${n*2} 24" preserveAspectRatio="none" aria-hidden="true"><path d="M0 24 ${Array.from({length:n},(_,i)=>`${i*2} ${24-(t.wave?.[i]??(.2+Math.sin(i*1.7)**2*.6))*23}`).join(' L')} L200 24Z"/></svg>`}
const priorRenderTimeline=renderTimeline;renderTimeline=function(){
 priorRenderTimeline();$('tracks').querySelectorAll('.clip-duration').forEach(el=>el.style.display=S.section==='clip'?'':'none');const w=$('editor').clientWidth/2;
 for(const c of S.musicClips){const t=musicTrack(c.trackId);if(!t)continue;const button=document.createElement('button');button.className='audio-lane music-segment '+(S.section==='music-clip'&&S.musicSelected===c.id?'music-selected':'');button.dataset.musicClip=c.id;button.style.cssText=`left:${w+c.at*pxPerSecond}px;top:${S.audioDetached?111:79}px;width:${musicDuration(c)*pxPerSecond}px`;button.innerHTML=`${waveform(t)}<span>${esc(t.name)} | ${musicDuration(c).toFixed(1)}s</span>`;$('tracks').append(button);}
};
const priorSync=syncMedia;syncMedia=function(){
 priorSync();const c=S.musicClips.find(c=>S.time>=c.at&&S.time<c.at+musicDuration(c)),t=c&&musicTrack(c.trackId);
 if(!t){customAudio.pause();return}
 if(customAudio.src!==t.url){customAudio.src=t.url;customAudio.load()}
 const pos=c.sourceStart+S.time-c.at;if(customAudio.readyState>=1&&Math.abs(customAudio.currentTime-pos)>.22)customAudio.currentTime=pos;
 customAudio.volume=Math.min(1,c.volume/100);if(S.playing)customAudio.play().catch(()=>{});else customAudio.pause();
};
function stopAudition(){musicPreview.pause();previewTrackId=null}
function closeMusicPicker(){stopAudition();$('musicPicker')?.remove()}
function openMusicPicker(){pause();stopAudition();renderMusicPicker()}
function renderMusicPicker(){
 $('musicPicker')?.remove();const layer=document.createElement('section');layer.id='musicPicker';layer.className='music-picker';layer.setAttribute('aria-label','Выберите музыку');
 layer.innerHTML=`<header class="music-picker-header"><button class="icon" data-music-action="back" aria-label="Назад">${svg('back')}</button><h2>Выберите музыку</h2></header><nav class="music-picker-tabs"><button data-music-tab="online" class="${pickerTab==='online'?'active':''}">Онлайн</button><button data-music-tab="device" class="${pickerTab==='device'?'active':''}">На устройстве</button></nav>${pickerTab==='online'?'<p class="music-empty">Онлайн-каталог в макете не подключён</p>':`<button class="music-import" data-music-action="import">${svg('musicImport')}<span>Импорт</span></button><div class="music-library">${musicLibrary.map((t,i)=>`<div class="music-library-row"><button class="music-cover" style="--cover:${['#a8c7cb','#d6b2bd','#cfbf9a'][i%3]}" data-music-preview="${t.id}" aria-label="${previewTrackId===t.id?'Остановить':'Прослушать'} ${esc(t.name)}">${svg(previewTrackId===t.id?'pause':'play')}</button><div class="music-track-info"><div>${esc(t.name)}</div><small>${previewTrackId===t.id?fmt(musicPreview.currentTime):'0:00'}/${fmt(t.duration)}</small></div><button class="icon music-remove" data-music-remove="${t.id}" aria-label="Удалить ${esc(t.name)} из списка">${svg('trash')}</button><button class="music-add" data-music-add="${t.id}" aria-label="Добавить ${esc(t.name)}">+</button></div>`).join('')||'<p class="music-empty">Импортируйте аудио с устройства</p>'}</div>`}`;
 $('editor').append(layer);
}
async function importMusic(files){
 for(const file of files){const url=URL.createObjectURL(file);ownedURLs.add(url);const a=new Audio();a.preload='metadata';a.src=url;
  try{const duration=await new Promise((ok,no)=>{const timer=setTimeout(()=>no(new Error('Не удалось прочитать длительность')),6000);a.onloadedmetadata=()=>{clearTimeout(timer);Number.isFinite(a.duration)&&a.duration>0?ok(a.duration):no(new Error('Неизвестная длительность'))};a.onerror=()=>{clearTimeout(timer);no(new Error('Аудиоформат не поддерживается браузером'))}});musicLibrary.push({id:'audio-'+Date.now()+'-'+musicLibrary.length,name:file.name.replace(/\.[^.]+$/,''),url,duration});}
  catch(error){URL.revokeObjectURL(url);ownedURLs.delete(url);toast(error.message)}finally{a.removeAttribute('src');a.load()}
 }
 pickerTab='device';renderMusicPicker();
}
function addMusic(id){const t=musicTrack(id);if(!t)return;commit();const old=selectedMusic(),c={id:'music-'+Date.now(),trackId:id,at:old?.at??0,sourceStart:0,sourceEnd:Math.min(t.duration,total()-(old?.at??0)),volume:old?.volume??100};if(old)S.musicClips=S.musicClips.map(x=>x.id===old.id?c:x);else S.musicClips.push(c);S.musicSelected=c.id;closeMusicPicker();chooseSection('music-clip')}
function makeDemoTrack(name,notes){
 const rate=16000,seconds=8,count=rate*seconds,buffer=new ArrayBuffer(44+count*2),v=new DataView(buffer),write=(off,s)=>[...s].forEach((c,i)=>v.setUint8(off+i,c.charCodeAt(0)));
 write(0,'RIFF');v.setUint32(4,36+count*2,true);write(8,'WAVEfmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,rate,true);v.setUint32(28,rate*2,true);v.setUint16(32,2,true);v.setUint16(34,16,true);write(36,'data');v.setUint32(40,count*2,true);
 const wave=Array(100).fill(0);for(let i=0;i<count;i++){const time=i/rate,phase=time%.5,freq=notes[Math.floor(time/.5)%notes.length],env=Math.min(1,phase/.02)*Math.exp(-phase*7)*Math.min(1,(seconds-time)*4),s=(Math.sin(2*Math.PI*freq*time)+.22*Math.sin(4*Math.PI*freq*time))*.12*env;v.setInt16(44+i*2,s*32767,true);wave[Math.min(99,Math.floor(i/count*100))]=Math.max(wave[Math.min(99,Math.floor(i/count*100))],Math.abs(s)*5)}
 const url=URL.createObjectURL(new Blob([buffer],{type:'audio/wav'}));ownedURLs.add(url);musicLibrary.push({id:'demo-audio-'+musicLibrary.length,name,url,duration:seconds,wave});
}
makeDemoTrack('Демо · Тёплые клавиши',[261.63,329.63,392,329.63,293.66,349.23,440,349.23]);
makeDemoTrack('Демо · Спокойный мотив',[220,261.63,329.63,392,329.63,261.63,246.94,293.66]);
const importInput=document.createElement('input');importInput.type='file';importInput.accept='audio/*';importInput.multiple=true;importInput.hidden=true;importInput.id='libraryMusicInput';document.body.append(importInput);importInput.onchange=()=>{importMusic([...importInput.files]);importInput.value=''};
Object.assign(actions,{
 extras:()=>chooseSection('extras'),more:()=>modal('pending'),aspect:()=>enter('aspect'),'add-music':openMusicPicker,'music-edit':openMusicPicker,'music-volume':()=>enter('music-volume'),
 'music-delete':()=>{const c=selectedMusic();if(!c)return;commit();S.musicClips=S.musicClips.filter(x=>x.id!==c.id);S.musicSelected=null;chooseSection('audio')},
 'music-copy':()=>{const c=selectedMusic();if(!c)return;const at=c.at+musicDuration(c),room=total()-at;if(room<.5){toast('Для копии нужно место после выбранного фрагмента');return}commit();const copy={...c,id:'music-'+Date.now(),at,sourceEnd:c.sourceStart+Math.min(musicDuration(c),room)};S.musicClips.push(copy);S.musicSelected=copy.id;render()},
 'music-split':()=>{const c=selectedMusic();if(!c)return;const offset=S.time-c.at;if(offset<.5||musicDuration(c)-offset<.5){toast('Каждый фрагмент должен быть не короче 0,5 с');return}commit();const cut=c.sourceStart+offset,right={...c,id:'music-'+Date.now(),at:S.time,sourceStart:cut};c.sourceEnd=cut;S.musicClips.push(right);S.musicSelected=right.id;render()}
});
$('editor').addEventListener('click',e=>{
 const ratio=e.target.closest('[data-project-ratio]');if(ratio){e.stopImmediatePropagation();commit();S.projectRatio=ratio.dataset.projectRatio;renderPanel();updatePreview();return}
 const lane=e.target.closest('[data-music-clip]');if(lane){e.stopImmediatePropagation();S.musicSelected=lane.dataset.musicClip;chooseSection('music-clip');return}
 const target=e.target.closest('[data-music-action],[data-music-tab],[data-music-preview],[data-music-add],[data-music-remove]');if(!target)return;e.stopImmediatePropagation();
 if(target.dataset.musicAction==='back')closeMusicPicker();else if(target.dataset.musicAction==='import')importInput.click();else if(target.dataset.musicTab){stopAudition();pickerTab=target.dataset.musicTab;renderMusicPicker()}
 else if(target.dataset.musicAdd)addMusic(target.dataset.musicAdd);
 else if(target.dataset.musicPreview){const id=target.dataset.musicPreview;if(previewTrackId===id){stopAudition();renderMusicPicker();return}stopAudition();const t=musicTrack(id);previewTrackId=id;musicPreview.src=t.url;musicPreview.play().catch(()=>{stopAudition();toast('Браузер не смог воспроизвести аудио');renderMusicPicker()});renderMusicPicker()}
 else if(target.dataset.musicRemove){const id=target.dataset.musicRemove;if(S.musicClips.some(c=>c.trackId===id)){toast('Сначала удалите этот звук с дорожки');return}if(previewTrackId===id)stopAudition();const i=musicLibrary.findIndex(t=>t.id===id);if(i>=0){URL.revokeObjectURL(musicLibrary[i].url);ownedURLs.delete(musicLibrary[i].url);musicLibrary.splice(i,1)}renderMusicPicker()}
},{capture:true});
$('panel').addEventListener('input',e=>{if(e.target.dataset.range!=='musicVolume')return;e.stopImmediatePropagation();if(!rangeChanging){commit();rangeChanging=true}const c=selectedMusic();if(c)c.volume=Number(e.target.value);updateRangeStyle(e.target);$('panel').querySelector('.volume-mark').textContent=e.target.value+'%';syncMedia()},{capture:true});
musicPreview.onended=()=>{previewTrackId=null;if($('musicPicker'))renderMusicPicker()};
document.addEventListener('keydown',e=>{if($('musicPicker')&&e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();closeMusicPicker()}},{capture:true});
const oldReset=actions['reset-project'];actions['reset-project']=()=>{closeMusicPicker();Object.assign(S,{projectRatio:'Исходное',musicClips:[],musicSelected:null});oldReset();chooseSection('main')};
window.addEventListener('resize',()=>updatePreview());$('video').addEventListener('loadedmetadata',()=>updatePreview());
const demoNote=document.createElement('p');demoNote.textContent='В выборе музыки доступны два синтезированных демозвука для прослушивания и добавления. Свои треки можно импортировать с устройства.';document.querySelector('.notes .scope-note').append(demoNote);
chooseSection('main');
