# ML 1.7.201 — рабочая контрольная точка и техническое ТЗ

Авторитетный подробный план (включая **поблочную спецификацию визуального
макета** и схему прав доступа) записан в существующую
[Сводку Google Drive](https://drive.google.com/file/d/1blEMbZmmAujpm5C9pZB0iqZ5bfhtHHRA/view).
Предыдущая редакция Сводки сохранена отдельным архивом.

Требования: QA-201-01 cover lifecycle; QA-201-02 измерение/оптимизация;
QA-201-03 independent foreground runner; QA-201-04 Activity-owned permission
approval/pause/resume; QA-201-05 dialog/collapsed surface/notification;
QA-201-06 detailed privacy-safe logging; QA-201-07 full CI, signer, Drive
round-trip and separate hardware QA.

Текущий commit содержит **только первую исследовательскую партию**:
неразрушающая теплая обложка после Lazy dispose и базовые монотонные
замеры времени стадий перемещения. Никакой отдельной служебной операции,
кнопки сворачивания или ускорения без метрик пока нет.
Не выпускать промежуточный APK, не merge, не считать Android QA пройденной.
