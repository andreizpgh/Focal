# Продолжение Focal · 2026-11-04

Продолжи в существующем checkout, ветка `codex/sessions-stt-handoff`,
draft PR [#1](https://github.com/andreizpgh/Focal/pull/1). Новый worktree не нужен.
Прочитай `AGENTS.md`, `docs/development-handoff.md`, `docs/quality-report.md`,
`docs/architecture.md`, `docs/local-emulator-qa.md` и `docs/device-validation.md`.
Подробные исследования находятся в `docs/benchmarks/2026-11-03-local/report.md`
и `docs/benchmarks/2026-11-03-stt/report.md`; не повторяй их ради восстановления контекста.

## Приоритет и организация

Ближайшая цель — следующий проверенный APK с исправлением владения памятью
движка. Заверши его Android-проверки, обычный полный QA и ручной интерфейсный
проход, затем сохрани проверенные коммиты, обнови PR и выдай APK. До этого не
расширяй STT-исследование. Пользователь просит сократить лишнюю работу без потери
качества и явно разрешил фокусированных субагентов GPT-6.1 Sol xhigh; root
отвечает за интеграцию и приёмку. Новые пользовательские чаты не нужны.

Тяжёлые процессы запускай по одному: сборка, нативный inference, Android QA.
Используй пониженный приоритет, CPU affinity 0–3, не более двух Gradle/CMake
workers, без parallel Gradle. Модели повторно не скачивай; длительные проверки
выполняй локально, Actions экономно. Не запускай `gradlew clean`: ignored `build/`
содержит невоспроизводимые без загрузок артефакты и сохранённые попытки.

## Актуальный Android checkpoint

Последний полный обычный QA — чистый `101a153`: 85 Android-тестов без ошибок
и пропусков, восемь обязательных real-model checks, 36 core tests,
debug/release lint, debug/test и unsigned release/R8. Точный проверенный APK
архивирован в `build/local-diagnostics/search-return-verification-20261104/verified-apks/`;
его SHA — в quality report. Это рабочая тестовая версия, не финальный релиз.

Исходный oversized-editor сбой исправлен в `12e958c`: input connection повторял
accepted draft и стирал предупреждение. Причина установлена наблюдением UI,
проверки не ослаблены. Дополнительно исправлены permission feedback, Unicode
названия, Rename focus/scrolling и поиск/caret. Сохраняй Sessions / Focus wave,
тихий английский интерфейс, явный язык речи, light с чёрными акцентами и dark/system.

Ownership-интеграция прошла адресные Android-тесты. Isolated debug baseline
подтвердил native -7 и падение при последующем JNI close; не повторять.
Patched attempt02: два теста без ошибок/пропусков, 173.937 s. Аудио, reviewed
правка и очередь сохранены; обычное восстановление новым движком успешно.
Отмена/close сохраняет owner до return, не отдаёт partial output, освобождает
ресурсы один раз. Первый cancellation run не дождался checkpoint за 30 s;
после независимого ревью arrival limit 120 s достиг checkpoint за 34.520 s.
Barrier hold остался 30 s, completion/cleanup 45 s; assertions не ослаблены.
Прежняя failed attempt сохранена. Источники/артефакты independently verified.

Обычный APK теперь включает ownership overlay без fault hooks. Обычная
сборка/core/lint/release прошла за 4m42s; артефакты находятся в
`build/local-diagnostics/ownership-app-verification-20261104/`.
**Сначала прочитай `build/local-diagnostics/current-android-delivery-work.json`
и свежие QA результаты; проверь процессы перед новым тяжёлым запуском.** Далее
проверка обоих ABI/DEX на отсутствие helper, source checkpoint, полный
`scripts/qa-local.py --release-checks` и ручной UI. Не засчитывай
успех adb как PASS без точных test IDs/counts/status. Адресный результат находится
в `build/local-diagnostics/android-native-fault-patched-attempt02/`.

## Завершённый нативный gate

Ownership-only overlay `cmake/focal-whisper-lifetime.cmake` даёт exact postimage
`8cc010e36be180b2843287562cd69104a2b1f11e4d7a95827fffce7db70ed64b`.
Submodule `4979e04f5dcaccb36057e059bbaed8a2f5288315` не менялся.
Model-free: patched 24/24; actual-model composite: patched 15/15,
baseline пять здоровых controls и десять ожидаемых regressions.
Все 30 уникальных case/variant pairs покрыты. Составное покрытие — 23 ранее
успешных случая + семь из отдельного продолжения, а не один чистый прогон.
Прежние 60 s timeout и SIGXCPU сохранены; функциональные oracles не менялись.
Результат continuation — `7e3d7e03…cdc7`; независимый postrun review PASS.
Не повторяй ни model-free suite, ни 30 завершённых случаев без новой причины.
Это проверка ownership, не all-language STT, arbitrary OOM или phone performance.

## Эмулятор и данные

Dedicated AVD — `focal_local_qa`, serial `emulator-5554`,
`ANDROID_AVD_HOME="$PWD/build/avd"`. KVM работает (`-accel on`); выключен Vulkan,
а не CPU acceleration. Host GLES, два vCPU, requested 2048/effective 2560 MiB,
320×640/160dpi. Другие AVD/данные не менять, не wipe/uninstall. `-no-audio`
не проверяет настоящий микрофон или слышимое воспроизведение.

Общий download ledger продолжается: **881 742 333 / 1 000 000 000 байт**
зарезервировано, остаток **118 257 667**, включая прежнюю резервацию 61 000 000
на один будущий CI download. Счётчик не сбрасывать, повторы учитывать.
Модель base Q5_1 и корпус уже локальны. Свежие 42 held-out клипа **не открыты**;
облачная test-часть уже использована и не является новой независимой проверкой.

## Открытые требования

- Восстановление на слабом меняющемся шуме всё ещё может выдумывать текст.
  Constant zero/DC guard этого не решает. Не повышай общий RMS-порог ради PASS.
- Измеримое качество и скорость на всех семи языках не установлены. SOT/VAD/beam
  варианты отклонены из-за потерь речи, отрицаний или других regressions; не
  включать их по суммарному WER. Результаты и source-bound diagnosis сохранены.
  Original audio/ASR, ручные правки, provenance и Undo обязательны для коррекции.
- Телефон владелец подключит позже при себе. Его отсутствие не блокирует локальную
  разработку. Реальные микрофон/playback, TalkBack, ARM latency/memory, батарея,
  нагрев, screen-off/calls/OEM background и русская живая диктовка остаются открыты.
- Стабильная release-подпись и проверка точного release APK ещё нужны. Не заменять
  опубликованный alpha непроверенным APK и не объявлять финализацию завершённой.

Сохраняй оригинальное аудио и транзакционное восстановление; inference вне capture,
без неограниченных PCM queues, Internet permission, analytics или accounts.
Коммиты/код — English, сообщения владельцу — коротко по-русски. Перед публикацией
staged diff review и full-history Gitleaks. В Git не публиковать модели, аудио,
транскрипты, сырые логи, локальную конфигурацию и ключи. Все проверенные изменения
сохраняй в этой ветке и существующем PR; новые Actions пока не нужны.
