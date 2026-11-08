# تقرير توحيد وتحديث QAMZOR — 0.7.0-dev

التاريخ: 2026-11-03

## ما تم توحيده

- اعتماد شجرة `QAMZOR-platform-continuation-2026-11-03.zip` كأساس موحد لأنها تجمع مرجع Python الأحدث لـ QZRA 1.3 مع تطبيق Android ومشروع Kotlin وDesktop وSDK.
- الإبقاء على تحديث Android الخاص باستيراد المجلدات وحماية الأرشيف بكلمة مرور. هذه الحماية في Kotlin الحالية هي حماية أرشيف QZRA 1.2 بالكامل، وليست كلمات مرور مستقلة للمجلدات.
- توحيد رقم إصدار المنتج التطويري إلى `0.7.0-dev` في Python وAndroid وDesktop وKotlin SDK.
- تحديث مقارنة Python لتفك وتتحقق من محتوى كل ملف وتعيد حساب SHA-256 قبل مقارنة البصمات افتراضيًا. أضيف دعم كلمات مرور المجلدات المستقلة في واجهة Python API وأوامر CLI للمقارنة، مع خيار صريح للمقارنة الوصفية السريعة.
- إضافة `ArchiveEngine.compareContent` في Kotlin Core، وتوصيلها بواجهة SDK وDesktop وAndroid. تدعم مقارنة Kotlin الأرشيفات التي يستطيع Kotlin Core قراءتها حاليًا (QZRA 1.0–1.2)، وتتحقق من كامل المحتوى قبل مقارنة SHA-256.
- إضافة سجل عمليات داخل جلسة Android يعرض العمليات الجارية والمكتملة والفاشلة. السجل داخل الذاكرة فقط، وليس خدمة Android دائمة أو استئنافًا بعد إغلاق التطبيق.

## حدود الدعم بعد التحديث

| الميزة | الحالة الدقيقة |
|---|---|
| QZRA 1.3 | منفذ في Python Reference فقط. Kotlin Core ما زال حتى 1.2؛ لا يوجد بعد قارئ/كاتب Kotlin 1.3 متوافق مع fixture المرجعي. |
| كلمات مرور مجلدات Android | غير مكتملة. Android يدعم كلمة مرور للأرشيف كله عبر QZRA 1.2، وليس مفاتيح ونطاقات مستقلة لكل مجلد. |
| مركز عمليات Android | أضيف سجل نشاط داخل التطبيق؛ لا توجد بعد خدمة WorkManager مستمرة، أو استئناف بعد إعادة التشغيل، أو إلغاء تعاوني لكل المحركات. |
| مقارنة SHA-256 | مقارنة تحقق المحتوى في Python API/CLI وKotlin Core وواجهات Android/Desktop. Kotlin حاليًا للأرشيفات 1.0–1.2. Python يمكنه التحقق من 1.3 بشرط توفير master password وكلمات مرور النطاقات المحمية. |
| Office | استخراج نصي لـ DOCX/XLSX/PPTX في Python. Android يعرض PDF والصور بصريًا، لكن لا يوجد بعد renderer يحافظ على تنسيق ملفات Office. |
| Google Drive / Dropbox / OneDrive | غير مدمجة. |
| S3 | غير مدمج. |
| APK / Desktop build | لم يُبنَ في بيئة هذه الدفعة؛ لا يوجد Gradle أو Android SDK مثبتان محليًا. |

## نتائج الفحص

- `PYTHONPATH=src python -m unittest discover -s tests -q`: 82 اختبارًا ناجحًا.
- `python -m compileall -q src tests tools`: ناجح.
- `qz --help`: ناجح.
- تم إنشاء wheel جديد للإصدار `0.7.0.dev0` والتحقق من محتوياته.
- فحص Kotlin بواسطة `kotlinc 1.9.0` لم يُظهر أخطاء تحليل نحوي (`expecting` / `unexpected tokens`)، لكنه لم ينجح كترجمة للمشروع لأن اعتماديات Gradle/Android SDK غير مثبتة. هذا ليس بديلًا عن `gradle :core:test :sdk:test :desktopApp:installDist` أو `gradle :androidApp:assembleDebug`.

## متطلبات إكمال النواقص

يلزم تنفيذ Kotlin QZRA 1.3 واختبار التوافق الثنائي مع `fixtures/qzra-1.3/python-reference-protected.qzra`، ثم واجهة فتح/قفل كلمات مرور المجلدات في Android. كما يلزم WorkManager وعمليات قابلة للإلغاء، Office renderer مرئي، موصلات مزودين حقيقية بإدارة OAuth، وS3 API. يجب تشغيل Gradle وAndroid SDK ورفع تقارير CI قبل إعلان APK أو Desktop قابلين للتوزيع.
