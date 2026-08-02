#!/bin/bash
adb -s 29eb447c install -r "h:/work/omnidocs/app/build/outputs/apk/debug/app-debug.apk" 2>&1
echo "ADB_EXIT=$?"
