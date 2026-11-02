@echo off
chcp 65001 >nul
echo ===== Live MCQ: সব টেস্ট একসাথে =====
echo এটি mvn test চালাবে, তারপর সার্ভার চালু করে Student/Teacher/Admin এর পুরো যাত্রা পরীক্ষা করবে।
echo সময় লাগবে কয়েক মিনিট (প্রথমবার ইন্টারনেট থেকে প্যাকেজ নামবে). অপেক্ষা করুন...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0smoke-test.ps1"
echo.
echo শেষ। প্রজেক্ট ফোল্ডারের report.txt ফাইলটি Claude কে পাঠান।
pause
