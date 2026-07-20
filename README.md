# DataTrafficGuard

ניטור צריכת נתונים בזמן אמת per-app + חסימה/היתר רשת לפי אפליקציה, כולל מצב אוטומטי
"פורגראונד בלבד" (רשת זמינה רק לאפליקציה הפעילה במסך). ראו את מסמך האפיון המלא
`data-traffic-guard-spec.md` לפרטי הארכיטקטורה.

## מבנה הפרויקט
- `app/src/main/java/.../vpn/VpnGuardService.kt` — VpnService מקומי, שולט מי מקבל רשת
- `app/src/main/java/.../monitor/ForegroundWatcherService.kt` — זיהוי אפליקציה בחזית
- `app/src/main/java/.../monitor/DataUsageRepository.kt` — נתוני צריכה (NetworkStatsManager + TrafficStats)
- `app/src/main/java/.../policy/PolicyEngine.kt` — לוגיקת allow/block, ידני + אוטומטי
- `app/src/main/java/.../MainActivity.kt` — Compose UI, Dashboard ראשוני

כרגע כל שכבות ה-vpn/monitor/policy הן שלד (TODO) בלבד — ה-build עובד ומריץ מסך
Dashboard בסיסי עם מתג "מצב נסיעה" (עדיין לא מחובר ללוגיקה).

## בנייה
דרך GitHub Actions (`.github/workflows/build.yml`) — כל push ל-`main` בונה APK debug
ומעלה אותו כ-artifact בשם `app-debug`.

בנייה מקומית (Termux):
```
./gradlew assembleDebug
```
