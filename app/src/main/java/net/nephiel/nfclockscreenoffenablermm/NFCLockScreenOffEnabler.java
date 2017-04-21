package net.nephiel.nfclockscreenoffenablermm;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XModuleResources;
import android.media.SoundPool;
import android.nfc.NfcAdapter;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

public class NFCLockScreenOffEnabler implements IXposedHookZygoteInit, IXposedHookLoadPackage {
	// Thanks to Tungstwenty for the preferences code, which I have taken from his Keyboard42DictInjector and made a bad job of it
	private static final String MY_PACKAGE_NAME = NFCLockScreenOffEnabler.class.getPackage().getName();
	private String MODULE_PATH;

	private SharedPreferences prefs;
	//private XSharedPreferences prefs;
	private Context mContext = null;

	private XModuleResources modRes = null;
	private SoundPool mSoundPool = null;
	private Object nfcServiceObject = null;

	// TODO: Get this dynamically from NfcService class
	// Taken from NfcService.java, Copyright (C) 2010 The Android Open Source Project, Licensed under the Apache License, Version 2.0
	// Screen state, used by mScreenState
	//private static final int SCREEN_STATE_OFF = 1;
	private static final int SCREEN_STATE_ON_LOCKED = 2;
	private static final int SCREEN_STATE_ON_UNLOCKED = 3;
	private static final int SOUND_START = 0;
	private static final int SOUND_END = 1;
	private static final int SOUND_ERROR = 2;

	private int mTagLostSound;
	
	private boolean mDebugMode = false;
	private static Set<String> mSoundsToPlayList;
	// --Commented out by Inspection (4/21/17 3:53 PM):protected Object mViewMediatorCallback;

	// Prevent multiple registers.
	//private boolean mIsOemStupid;
	private static Object mScreenStateHelper; 

	// Hook for NfcNativeTag$PresenceCheckWatchdog.run()
	class PresenceCheckWatchdogRunHook extends XC_MethodHook {
		private static final String TAG = "PresenceCheckWatchdogRunHook";

		// Using default timeout for now
		/*
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			if (!prefs.getBoolean(Common.PREF_TAGLOST, true))
				return;

			// The timeout is final in KitKat, no easy way to change it
			if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT) {
				XposedHelpers.callMethod(param.thisObject, "setTimeout",
						Integer.parseInt(prefs.getString(Common.PREF_PRESENCE_CHECK_TIMEOUT,
								"2000")));
			}
		}
		*/

		@Override
		protected void afterHookedMethod(MethodHookParam param) throws Throwable {
			if (!prefs.getBoolean(Common.PREF_TAGLOST, true))
				return;

			// broadcast tag lost message
			try {  
				Class<?> activityManagerNative = Class.forName("android.app.ActivityManagerNative");  

				if (activityManagerNative != null) {
					Object am = activityManagerNative.getMethod("getDefault").invoke(activityManagerNative);  

					if (am != null) {
						am.getClass().getMethod("resumeAppSwitches").invoke(am);
					}
				}
			} catch (Exception e) { 
				XposedBridge.log("PresenceCheckWatchdogRunHook afterHookedMethod threw exception (1)" + e.getMessage());
				e.printStackTrace();
			}  

			Context context = (Context) XposedHelpers.getAdditionalInstanceField(XposedHelpers.getSurroundingThis(param.thisObject), "mContext");

			if (context == null) {
				if (mDebugMode) Log.d(TAG.substring(0,22),  "step-4 context == null");
				return;
			}

			try {
				byte[] uId = (byte[]) XposedHelpers.callMethod(XposedHelpers.getSurroundingThis(param.thisObject), "getUid");

				Common.sendTagChangedBroadcast(context, uId, false);

				Intent intentToStart = new Intent(Common.ACTION_TAG_LOST);
				intentToStart.putExtra(NfcAdapter.EXTRA_ID, uId);
				intentToStart.putExtra(Common.EXTRA_ID_STRING, Common.byteArrayToHexString(uId));

				Common.sendBroadcast(context, intentToStart);

				intentToStart.setData(null);
				intentToStart.setType(null);
				intentToStart.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

				PackageManager packageManager = context.getPackageManager();

				if (packageManager != null) {
					List<ResolveInfo> activities = packageManager.queryIntentActivities(intentToStart, 0);
					if (activities.size() > 0) {
						if (mDebugMode) Log.d(TAG.substring(0,22), String.format("startActivity - android.nfc.action.TAG_LOST(%x%x%x%x)",
								uId[0], uId[1], uId[2], uId[3]));
						context.startActivity(intentToStart);
					} else {
						if (mDebugMode) Log.d(TAG.substring(0,22),  String.format("activities.size() <= 0 (%x%x%x%x)",
								uId[0], uId[1], uId[2], uId[3]));
					}
				}

				playTagLostSound();
			} catch (Exception e) {
				XposedBridge.log("PresenceCheckWatchdogRunHook afterHookedMethod threw exception (2)" + e.getMessage());
				e.printStackTrace();
			}  
		}
	}
	
	//Hook for NfcService.onRemoteEndpointDiscovered(TagEndpoint tag)
    private class NfcServiceOnRemoteEndpointDiscoveredHook extends XC_MethodHook {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			try {
				byte[] uuid = (byte[]) XposedHelpers.callMethod(param.args[0], "getUid");
				String uuidString = Common.byteArrayToHexString(uuid);

				Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
				if (mDebugMode)	XposedBridge.log("Got NFC tag: " + uuidString.trim());

				if (context != null) {
					KeyguardManager kmgr = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
					PowerManager pwrmgr = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
					Common.sendTagChangedBroadcast(context, uuid, true);

					boolean isKeyguardLocked;
					//if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
					//	isKeyguardLocked = kmgr.isKeyguardLocked();
					//} else {
						isKeyguardLocked = kmgr.inKeyguardRestrictedInputMode();
					//}
					if (mDebugMode) XposedBridge.log("isKeyguardLocked = " + isKeyguardLocked);
					if (mDebugMode) XposedBridge.log("pwrmgr.isInteractive() = " + pwrmgr.isInteractive());
					/*
					if (isKeyguardLocked || !pwrmgr.isInteractive()) {
						if (mDebugMode) XposedBridge.log("Device screen is off, or keyboard is locked.");
						context.sendBroadcast(new Intent(Common.INTENT_UNLOCK_DEVICE));
					} else {
						if (mDebugMode) XposedBridge.log("Device screen is on but keyboard is not locked, ignoring tag.");
					}
					*/
					
					if (!prefs.getBoolean(Common.PREF_TAGLOST, true))
						return;

					XposedHelpers.setAdditionalInstanceField(param.args[0], "mContext", context);
				}
			} catch (Exception e) {
				XposedBridge.log("NfcServiceOnRemoteEndpointDiscoveredHook beforeHookedMethod threw exception: " + e.getMessage());
				e.printStackTrace();
			}  
		}
	}

	// Thanks to rovo89 for his suggested improvements: http://forum.xda-developers.com/showpost.php?p=35790508&postcount=185
	@SuppressWarnings("deprecation")
	@Override
	public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
		prefs = AndroidAppHelper.getSharedPreferencesForPackage(MY_PACKAGE_NAME,
				Common.PREFS, Context.MODE_PRIVATE);
		//prefs = new XSharedPreferences(MY_PACKAGE_NAME, Common.PREFS).makeWorldReadable();
		MODULE_PATH = startupParam.modulePath;
		mDebugMode = prefs.getBoolean(Common.PREF_DEBUG_MODE, false);
	}
	
	private void playTagLostSound() {
		if (!mSoundsToPlayList.contains("sound_taglost"))
			return;
		
		synchronized (nfcServiceObject) {
			if (mSoundPool == null) {
				if (mDebugMode) {
					Log.w("NfcService", "Not playing sound when NFC is disabled");
				}
				return;
			}
			mSoundPool.play(mTagLostSound, 1.0f, 1.0f, 0, 0, 1.0f);
		}
	}

	private void reloadSoundsToPlayList() {
		HashSet<String> defaultSounds = new HashSet<>();
		defaultSounds.add("sound_start");
		defaultSounds.add("sound_error");
		defaultSounds.add("sound_end");
		mSoundsToPlayList = prefs.getStringSet(Common.PREF_SOUNDS_TO_PLAY, defaultSounds);
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (lpparam.packageName.equals(Common.PACKAGE_NFC)) {
			modRes = XModuleResources.createInstance(MODULE_PATH, null);
			reloadSoundsToPlayList();

			Class<?> NfcService = null;

			/*
			// Fuck you LG
			mIsOemStupid = false;
			try {
				NfcService = findClass(Common.PACKAGE_NFC + ".LNfcService", lpparam.classLoader);
				mIsOemStupid = true;
			} catch (ClassNotFoundError e) {}
			
			if (mDebugMode)	XposedBridge.log("mIsOemStupid = " + mIsOemStupid);
			*/
			
			//if (NfcService == null) {
				try {
					NfcService = findClass(Common.PACKAGE_NFC + ".NfcService", lpparam.classLoader);
				} catch (ClassNotFoundError e) {
					XposedBridge.log("Class NfcService not found: " + e.getMessage());
					e.printStackTrace();
				}
			//}
			
			// Don't reload settings on every call, that can cause slowdowns.
			// This intent is fired from NFCLockScreenOffEnablerActivity when
			// any of the parameters change.
			XC_MethodHook initNfcServiceHook = new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					nfcServiceObject = param.thisObject;

					mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
					mContext.registerReceiver(new BroadcastReceiver() {

						@SuppressWarnings("deprecation")
						@Override
						public void onReceive(Context context, Intent intent) {
							if (mDebugMode)	XposedBridge.log(MY_PACKAGE_NAME + ": " + "Settings updated, reloading...");
							AndroidAppHelper.reloadSharedPreferencesIfNeeded(prefs);
							//prefs.reload();

							// This may be faster than using prefs.getBoolean, since we use this a lot.
							mDebugMode = prefs.getBoolean(Common.PREF_DEBUG_MODE, false);
							reloadSoundsToPlayList();
						}
					}, new IntentFilter(Common.SETTINGS_UPDATED_INTENT));
					
					try {
						mScreenStateHelper = XposedHelpers.getObjectField(param.thisObject, "mScreenStateHelper");
					} catch (NoSuchFieldError e) {
						XposedBridge.log("Field mScreenStateHelper not found: " + e.getMessage());
						e.printStackTrace();
					}
				}
			};

			boolean hookedSuccessfully = true;
			try {
				Constructor<?> NfcServiceConstructor =
						XposedHelpers.findConstructorBestMatch(NfcService, Application.class);

				XposedBridge.hookMethod(NfcServiceConstructor, initNfcServiceHook);
			} catch (NoSuchMethodError e) {
				XposedBridge.log("Method NfcServiceConstructor not found: " + e.getMessage());
				hookedSuccessfully = false;
			}

			if (!hookedSuccessfully) {
				try {
					findAndHookMethod(NfcService, "onCreate", initNfcServiceHook);
				} catch (NoSuchMethodError e) {
					XposedBridge.log("Method onCreate not found: " + e.getMessage());
					e.printStackTrace();
				}
			}

			try {
				findAndHookMethod(NfcService, "initSoundPool", new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						mSoundPool = (SoundPool) XposedHelpers.getObjectField(param.thisObject, "mSoundPool");
						synchronized (param.thisObject) {
							if (mSoundPool != null) {
								mTagLostSound =	mSoundPool.load(modRes.openRawResourceFd(R.raw.tag_lost), 1);
							}
						}
					}
				});
			} catch (NoSuchMethodError e) {
				XposedBridge.log("Method initSoundPool not found: " + e.getMessage());
				e.printStackTrace();
			}

			try {
				findAndHookMethod(NfcService, "playSound", int.class, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						int event = (Integer) param.args[0];
						if ((event == SOUND_START && !mSoundsToPlayList.contains("sound_start"))
								|| (event == SOUND_END && !mSoundsToPlayList.contains("sound_end"))
								|| (event == SOUND_ERROR && !mSoundsToPlayList.contains("sound_error"))) {
							param.setResult(false);
						}
					}
				});
			} catch (NoSuchMethodError e) {
				XposedBridge.log("Method playSound not found: " + e.getMessage());
				e.printStackTrace();
			}

			XC_MethodHook checkScreenStateHook = new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
					if (enableNfcWhen.equals("unlocked"))
						return;

					try {
						Boolean NeedScreenOnState = (Boolean)XposedHelpers.getAdditionalInstanceField(param.thisObject, "NeedScreenOnState") ;
						if (NeedScreenOnState == null || !NeedScreenOnState)
							return;

						param.setResult(SCREEN_STATE_ON_UNLOCKED);
					} catch (Exception e) {
						XposedBridge.log("checkScreenStateHook beforeHookedMethod threw exception: " + e.getMessage());
						e.printStackTrace();  
					}  
				}
			};

			// Nfc module of some kinds of ROMs may call checkScreenState in applyRouting
			// and update mScreenState, so we have to hook checkScreenState and modify
			// the return value
			//try {
			//	findAndHookMethod(NfcService, "checkScreenState", checkScreenStateHook);
			//} catch (NoSuchMethodError e) {
			//	try {
			//		findAndHookMethod(Common.PACKAGE_NFC + ".NfcService", lpparam.classLoader,
			//				"checkScreenState", checkScreenStateHook);
			//	} catch (NoSuchMethodError e1) {
					try {
						// From Lollipop onwards, checkScreenState method was moved to a separate ScreenStateHelper class
						findAndHookMethod(Common.PACKAGE_NFC + ".ScreenStateHelper", lpparam.classLoader,
								"checkScreenState", checkScreenStateHook);
					} catch (NoSuchMethodError e2) {
						XposedBridge.log("Method checkScreenState not found: " + e2.getMessage());
						e2.printStackTrace();
					}
			//	}
			//}

			try {
				//if (mIsOemStupid) {
				//	/* The subject seems to have shown signs of intelligence here.
				//	 * LG's implementation of NFC supports NFC while screen is off/locked.
				//	 * This might be because of their weird NFC sending feature, or not.
				//	 */
				//	findAndHookMethod(NfcService, "applyRouting", boolean.class, new XC_MethodHook() {
				//		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				//			final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
				//
				//			if (enableNfcWhen.equals("unlocked")) {
				//				XposedHelpers.setIntField(param.thisObject, "POLLING_MODE", 0);
				//			} else if (enableNfcWhen.equals("locked_screen_on")) {
				//				XposedHelpers.setIntField(param.thisObject, "POLLING_MODE", 2);
				//			//} else if (enableNfcWhen.equals("screen_off")) {
				//			//	XposedHelpers.setIntField(param.thisObject, "POLLING_MODE", 1);
				//			}
				//		};
				//	});
				//} else {
					findAndHookMethod(NfcService, "applyRouting", boolean.class, new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
							if (enableNfcWhen.equals("unlocked"))
								return;
							try {
								final int currScreenState;
								if (mScreenStateHelper != null) {
									currScreenState = (Integer) XposedHelpers.callMethod(mScreenStateHelper, "checkScreenState");
								} else {
									currScreenState = (Integer) XposedHelpers.callMethod(param.thisObject, "checkScreenState");
								}
								// We also don't need to run if the screen is already on, or if the user
								// has chosen to enable NFC on the lockscreen only and the phone is not locked
								if ((currScreenState == SCREEN_STATE_ON_UNLOCKED)
										|| (enableNfcWhen.equals("locked_screen_on")
												&& currScreenState != SCREEN_STATE_ON_LOCKED)) {
									XposedHelpers.setAdditionalInstanceField(param.thisObject, "mOrigScreenState", -1);
									return;
								}

								// we are in applyRouting, set the flag NeedScreenOnState to true
								XposedHelpers.setAdditionalInstanceField(param.thisObject, "NeedScreenOnState", true);

								synchronized (param.thisObject) { // Not sure if this is correct, but NfcService.java insists on having accesses to the mScreenState variable synchronized, so I'm doing the same here
									XposedHelpers.setAdditionalInstanceField(param.thisObject, "mOrigScreenState", XposedHelpers.getIntField(param.thisObject, "mScreenState"));
									XposedHelpers.setIntField(param.thisObject, "mScreenState", SCREEN_STATE_ON_UNLOCKED);
								}
							} catch (Exception e) {
								XposedBridge.log("applyRouting beforeHookedMethod threw exception: " + e.getMessage());
								e.printStackTrace();  
							}  
						}

						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							try {
								final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
								if (enableNfcWhen.equals("unlocked"))
									return;

								// exit from applyRouting, set the flag NeedScreenOnState to false
								XposedHelpers.setAdditionalInstanceField(param.thisObject, "NeedScreenOnState", false);

								final int mOrigScreenState = (Integer) XposedHelpers.getAdditionalInstanceField(param.thisObject, "mOrigScreenState");
								if (mOrigScreenState == -1)
									return;

								synchronized (param.thisObject) {
									// Restore original mScreenState value after applyRouting has run
									XposedHelpers.setIntField(param.thisObject, "mScreenState", mOrigScreenState);
								}
							} catch (Exception e) {
								XposedBridge.log("applyRouting afterHookedMethod threw exception: " + e.getMessage());
								e.printStackTrace();  
							}  
						}
					});
				//}
			} catch (NoSuchMethodError e) {
				XposedBridge.log("Method applyRouting not found: " + e.getMessage());
				e.printStackTrace();
			}

			try {
				findAndHookMethod(NfcService, "onRemoteEndpointDiscovered",
						Common.PACKAGE_NFC + ".DeviceHost$TagEndpoint",
						new NfcServiceOnRemoteEndpointDiscoveredHook());
			} catch (NoSuchMethodError e) {
				XposedBridge.log("Method not found: " + e.getMessage());
				e.printStackTrace();
			} catch (ClassNotFoundError e) {
				XposedBridge.log("Class not found: " + e.getMessage());
				e.printStackTrace();
			}
			
			try {
				Class<?> PresenceCheckWatchDog = findClass(Common.PACKAGE_NFC + ".dhimpl.NativeNfcTag$PresenceCheckWatchdog", lpparam.classLoader);
				findAndHookMethod(PresenceCheckWatchDog, "run", new PresenceCheckWatchdogRunHook());
			} catch (ClassNotFoundError e) {
				if (mDebugMode)	XposedBridge.log("Not hooking class .dhimpl.NativeNfcTag$PresenceCheckWatchdog");
				try {
					Class<?> PresenceCheckWatchdog = findClass(Common.PACKAGE_NFC +".nxp.NativeNfcTag$PresenceCheckWatchdog", lpparam.classLoader);
					findAndHookMethod(PresenceCheckWatchdog, "run", new PresenceCheckWatchdogRunHook());
				} catch (ClassNotFoundError e1) {
					if (mDebugMode)	XposedBridge.log("Not hooking class .nxp.NativeNfcTag$PresenceCheckWatchdog");
					XposedBridge.log("All attempts to hook class NativeNfcTag$PresenceCheckWatchdog failed");
				}
			}

		}
	}
}
