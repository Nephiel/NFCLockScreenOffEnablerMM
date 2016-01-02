NFCLockScreenOff for Marshmallow
===========================================================================

A Xposed Framework module to enable NFC on lockscreen, lost tag notification, and disable NFC sounds on stock Android 6.x

Based on https://github.com/MohammadAG/NFCLockscreenoffEnabler for KitKat

Modified for my personal use, mainly to disable the NFC sounds on a Nexus 5 with Android 6.0.1 without having to resort to mod NfcNci.apk (which causes bootloops and breaks future updates).

Current features:
* Enable or disable NFC sounds: scan start, scan end, error, tag lost
* Tag lost notification (timeout is not yet configurable, though)
* Enable NFC when the device screen is on but locked (Marshmallow already does this with the Smart Lock feature)

Caveats:
* The KitKat version (1.9.7.7) could register a list of authorized tags to unlock and dismiss the lockscreen. This feature was removed from the module because, from Lollipop onwards, Android comes with Smart Lock, which does the same. Mostly. Well, [not really](https://code.google.com/p/android/issues/detail?id=79928).
* The KitKat version (1.9.7.7) could also enable NFC when the screen is off, but on Marshmallow that prevents Doze from working, so I commented it out from this version. Uncomment it and experiment at your own peril.
* Now with 50% more deprecated code!

Contributors:
* qwerty12 (Main developer of module)
* madfish73 (Tag lost support)
* MohammadAG (NFC Unlocking, overall improvement of module)
* Nephiel (Marshmallow adaptation, Spanish translation)

Disclaimers:  
1) Don't use it on any device anyone cares about  
2) Don't blame me if you ignore 1) and bad stuff happens  

