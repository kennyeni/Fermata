package me.aap.fermata;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.media.engine.BitmapCache;
import me.aap.fermata.vfs.FermataVfsManager;
import me.aap.utils.app.App;
import me.aap.utils.app.NetSplitCompatApp;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.SharedPreferenceStore;
import me.aap.utils.ui.activity.ActivityDelegate;

/**
 * @author Andrey Pavlenko
 */
public class FermataApplication extends NetSplitCompatApp {
	private FermataVfsManager vfsManager;
	private BitmapCache bitmapCache;
	private volatile SharedPreferenceStore preferenceStore;
	private volatile AddonManager addonManager;
	private int mirroringMode;
	private ServiceConnection eventService;

	public static FermataApplication get() {
		return App.get();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		setupCrashLogger();
		testFileWrite(); // Verify file writing works
		vfsManager = new FermataVfsManager();
		bitmapCache = new BitmapCache();
	}

	private void testFileWrite() {
		try {
			File testDir = new File(getExternalFilesDir(null), "CrashLogs");
			testDir.mkdirs();
			File testFile = new File(testDir, "startup_test.txt");
			FileOutputStream fos = new FileOutputStream(testFile);
			PrintWriter pw = new PrintWriter(fos);
			pw.println("App started successfully at: " + new Date());
			pw.println("Path: " + testFile.getAbsolutePath());
			pw.close();
			fos.close();
			Log.i("Test file written to: " + testFile.getAbsolutePath());
		} catch (Exception e) {
			Log.e(e, "Failed to write test file");
		}
	}

	private void setupCrashLogger() {
		final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
			String crashInfo = null;
			try {
				// Try multiple locations
				File[] logDirs = new File[]{
					new File(getExternalFilesDir(null), "CrashLogs"),
					new File(getFilesDir(), "CrashLogs"),
					getCacheDir()
				};

				String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());

				for (File logDir : logDirs) {
					try {
						if (!logDir.exists()) logDir.mkdirs();

						File logFile = new File(logDir, "crash_" + timestamp + ".txt");
						FileOutputStream fos = new FileOutputStream(logFile);
						PrintWriter pw = new PrintWriter(fos);

						pw.println("=== Fermata Crash Log ===");
						pw.println("Time: " + timestamp);
						pw.println("Thread: " + thread.getName());
						pw.println("Build: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
						pw.println("Log location: " + logFile.getAbsolutePath());
						pw.println("\n--- Stack Trace ---\n");

						StringWriter sw = new StringWriter();
						throwable.printStackTrace(new PrintWriter(sw));
						crashInfo = sw.toString();
						pw.println(crashInfo);

						pw.flush();
						pw.close();
						fos.close();

						Log.i("Crash log saved to: " + logFile.getAbsolutePath());
						break; // Success, no need to try other locations
					} catch (Exception ignored) {
						// Try next location
					}
				}
			} catch (Exception e) {
				Log.e(e, "Failed to write crash log");
			}

			// Always log to logcat
			if (crashInfo != null) {
				Log.e("CRASH: " + crashInfo);
			}

			if (defaultHandler != null) {
				defaultHandler.uncaughtException(thread, throwable);
			}
		});
	}

	public boolean isConnectedToAuto() {
		return BuildConfig.AUTO && ActivityDelegate.getContextToDelegate() != null;
	}

	public FermataVfsManager getVfsManager() {
		return vfsManager;
	}

	public BitmapCache getBitmapCache() {
		return bitmapCache;
	}

	public PreferenceStore getPreferenceStore() {
		SharedPreferenceStore ps = preferenceStore;

		if (ps == null) {
			synchronized (this) {
				if ((ps = preferenceStore) == null) {
					preferenceStore =
							ps = SharedPreferenceStore.create(getSharedPreferences("fermata", MODE_PRIVATE));
				}
			}
		}

		return ps;
	}

	public SharedPreferences getDefaultSharedPreferences() {
		return ((SharedPreferenceStore) getPreferenceStore()).getSharedPreferences();
	}

	public AddonManager getAddonManager() {
		AddonManager mgr = addonManager;

		if (mgr == null) {
			synchronized (this) {
				if ((mgr = addonManager) == null) {
					addonManager = mgr = new AddonManager(getPreferenceStore());
				}
			}
		}

		return mgr;
	}

	@Override
	protected int getMaxNumberOfThreads() {
		return 5;
	}

	@NonNull
	@Override
	public File getLogFile() {
		File dir = getExternalFilesDir(null);
		if (dir == null) dir = getFilesDir();
		return new File(dir, "Fermata.log");
	}

	@Nullable
	@Override
	public String getCrashReportEmail() {
		return "andrey.a.pavlenko@gmail.com";
	}

	public boolean isMirroringMode() {
		return BuildConfig.AUTO && getMirroringMode() != 0;
	}

	public boolean isMirroringLandscape() {
		return BuildConfig.AUTO && getMirroringMode() == 1;
	}

	public int getMirroringMode() {
		return mirroringMode;
	}

	public void setMirroringMode(int mirroringMode) {
		if (!BuildConfig.AUTO) return;
		this.mirroringMode = mirroringMode;

		if (mirroringMode == 0) {
			if (eventService != null) {
				unbindService(eventService);
				eventService = null;
			}
		} else if (eventService == null) {
			eventService = new ServiceConnection() {
				@Override
				public void onServiceConnected(ComponentName name, IBinder service) {
					Log.d("Connected to XposedEventDispatcherService");
				}

				@Override
				public void onServiceDisconnected(ComponentName name) {
					Log.d("Disconnected from XposedEventDispatcherService");
				}
			};
			try {
				Log.i("Starting XposedEventDispatcherService");
				var i = new Intent();
				i.setComponent(
						new ComponentName(this, "me.aap.fermata.auto" + ".XposedEventDispatcherService"));
				bindService(i, eventService, Context.BIND_AUTO_CREATE);
			} catch (Exception err) {
				eventService = null;
				Log.e(err, "Failed to bind EventDispatcherService");
			}
		}
	}
}
