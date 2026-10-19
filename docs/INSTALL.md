# Installing Voodoo Browser

Open the signed `Voodoo-Browser-0.1.0.apk` on the phone. Android may ask you to allow installs from the app that opened the APK. Confirm installation through Android's installer.

With a computer connected to the phone and USB debugging authorized:

```sh
adb devices
adb install -r Voodoo-Browser-0.1.0.apk
adb shell am start -n com.ether4o4.voodoo/.MainActivity
```

The build environment did not have an attached Android device, so these device steps have not been run here. Do not uninstall before installing updates: `-r` installs over the existing app and preserves its data when the package ID and signing certificate match.

Keep the private signing recovery archive. It contains the keystore and its password, and must stay outside the public repository. Export a Voodoo data backup before changing phones or clearing app data. Provider keys must be entered again on a new installation; they are not exported.

The homepage image can be selected through **AI → Settings → Homepage image**. The title is exactly **Voodoo browser**.
