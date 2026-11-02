/* Screenshot confirms progress overlay, no export-settings sheet. UI demo only. */
let saveDemoTimer=null,saveDemoProgress=0;
function updateSaveProgress(value){saveDemoProgress=Math.max(0,Math.min(100,value));const track=$('saveProgressTrack');if(!track)return;track.setAttribute('aria-valuenow',saveDemoProgress);$('saveProgressValue').textContent=saveDemoProgress+' %';$('saveProgressFill').style.width=saveDemoProgress+'%'}
const priorRenderModal=renderModal;
renderModal=function(){
 if(S.modal!=='save'){clearInterval(saveDemoTimer);saveDemoTimer=null;priorRenderModal();return}
 $('modalLayer').innerHTML=`<div class="save-shade"><div class="save-progress-dialog" role="dialog" aria-modal="true" aria-labelledby="saveProgressMessage"><div class="save-progress-row"><p id="saveProgressMessage" class="save-progress-message">Оставайтесь на этой странице до завершения сохранения объектов...</p><span class="save-progress-value" id="saveProgressValue"></span></div><div class="save-progress-track" id="saveProgressTrack" role="progressbar" aria-label="Демонстрация сохранения" aria-valuemin="0" aria-valuemax="100"><i id="saveProgressFill"></i></div></div></div>`;
 updateSaveProgress(saveDemoProgress);
 if(!saveDemoTimer)saveDemoTimer=setInterval(()=>{if(S.modal!=='save'){clearInterval(saveDemoTimer);saveDemoTimer=null;return}updateSaveProgress(saveDemoProgress+2);if(saveDemoProgress===100){S.modal=null;renderModal();toast('Демонстрация завершена. HTML-макет не создаёт MP4.')}},100);
};
actions.save=()=>{saveDemoProgress=0;modal('save')};
