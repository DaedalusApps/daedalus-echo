import subprocess
import time
import sys
import re

def run_cmd(cmd):
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        return result.stdout.strip(), result.stderr.strip(), result.returncode
    except Exception as e:
        return "", str(e), 1

def main():
    print("=== Daedalus Setup Verification ===")
    
    # Gate 1: Phone Connection
    print("[Gate 1] Checking Phone Connection (ADB)...")
    out, err, code = run_cmd("adb devices")
    devices = [line for line in out.splitlines()[1:] if line.strip()]
    
    if not devices:
        print("❌ FAILED: No phone detected via ADB. Ensure USB debugging is enabled.")
        sys.exit(1)
    
    print(f"✅ PASSED: Phone detected: {devices[0]}")

    # Gate 2: Recorder Connection (Sync Test)
    print("\n[Gate 2] Checking Recorder Connection (Auto-Sync)...")
    
    # Clear logcat first
    run_cmd("adb logcat -c")
    
    # Trigger Sync
    print("   -> Triggering com.daedalus.voicenotes.SYNC broadcast...")
    out, err, code = run_cmd("adb shell am broadcast -a com.daedalus.voicenotes.SYNC")
    
    if "Broadcast completed" not in out:
        print("❌ FAILED: Could not trigger sync broadcast. Is the app installed and running?")
        sys.exit(1)

    # Monitor logs for 5 seconds
    print("   -> Monitoring DaedalusSync logs for 5s...")
    deadline = time.time() + 5
    recorder_found = False
    volumes_found = []
    
    while time.time() < deadline:
        log_out, _, _ = run_cmd("adb logcat -d -s DaedalusSync")
        
        # Look for recorder folder success
        if "Found recorder folder at" in log_out:
            recorder_found = True
            break
            
        # Collect volume info for debugging if failed
        vol_matches = re.findall(r"Checking volume: (.*?) at (.*)", log_out)
        for v in vol_matches:
            if v not in volumes_found:
                volumes_found.append(v)
        
        time.sleep(0.5)

    if recorder_found:
        print("✅ PASSED: Recorder detected and accessible!")
    else:
        print("❌ FAILED: Recorder folder not found.")
        if volumes_found:
            print("\n   Detected Volumes (none had a RECORD folder):")
            for vol, path in volumes_found:
                print(f"    - {vol} (Path: {path})")
        else:
            print("   No external storage volumes detected at all. Is the OTG adapter connected?")
        sys.exit(1)

    print("\n=== All Gates Passed! ===")

if __name__ == "__main__":
    main()
