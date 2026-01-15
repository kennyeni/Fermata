package me.aap.fermata.auto;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
import static me.aap.fermata.ui.activity.MainActivityDelegate.INTENT_ACTION_FINISH;
import static me.aap.utils.ui.UiUtils.toIntPx;
import static me.aap.utils.ui.UiUtils.toPx;
import static me.aap.utils.ui.activity.ActivityDelegate.FULLSCREEN_FLAGS;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.collection.CollectionUtils;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.ui.view.MovableRecyclerViewAdapter;

public class LauncherActivity extends AppCompatActivity {
	public static final String INTENT_EXTRA_MODE = "fermata.mirror.mode";
	private static final Pref<Supplier<String[]>> AA_LAUNCHER_APPS = Pref.sa("AA_LAUNCHER_APPS",
			() -> new String[]{FermataApplication.get().getPackageName(), "com.android.chrome",
					"com.google.android.gm", "com.google.android.apps.maps", "com.google.android.youtube"});
	private static LauncherActivity activeInstance;

	static LauncherActivity getActiveInstance() {
		return activeInstance;
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		var intent = getIntent();
		if ((intent != null) && INTENT_ACTION_FINISH.equals(intent.getAction())) {
			finish();
			return;
		}

		MainActivityDelegate.setTheme(this, true);
		setRequestedOrientation();
		var v = new AppListView(this);
		setContentView(v);
		var w = getWindow();
		w.addFlags(
				FLAG_KEEP_SCREEN_ON | FLAG_TURN_SCREEN_ON | FLAG_DISMISS_KEYGUARD | FLAG_SHOW_WHEN_LOCKED);
		w.getDecorView().setSystemUiVisibility(FULLSCREEN_FLAGS);
		getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (v.selectApps != null) v.selectApps();
			}
		});
		FermataApplication.get().getHandler()
				.postDelayed(() -> v.configure(this, getResources().getConfiguration()), 1000);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (INTENT_ACTION_FINISH.equals(intent.getAction())) finish();
	}

	@Override
	protected void onResume() {
		super.onResume();
		activeInstance = this;
		setRequestedOrientation();
	}

	@Override
	protected void onPause() {
		super.onPause();
		activeInstance = null;
	}

	private void setRequestedOrientation() {
		var land = FermataApplication.get().isMirroringLandscape();
		setRequestedOrientation(
				land ? SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
						SCREEN_ORIENTATION_SENSOR_PORTRAIT);
	}

	private static final class AppListView extends RecyclerView {
		private final List<AppInfo> apps;
		private SelectAppInfo[] selectApps;
		private final Animation animation;
		private final Drawable addIcon;
		private Drawable exitIcon;
		private Drawable backIcon;
		private Drawable checkedIcon;
		private Drawable uncheckedIcon;
		private int iconSize;
		private int marginH;
		private final int marginV;
		private final int textMargin;

		public AppListView(@NonNull Context ctx) {
			super(ctx);
			apps = loadAppList();
			animation = AnimationUtils.loadAnimation(ctx, me.aap.utils.R.anim.button_press);
			addIcon = loadIcon(R.drawable.add_circle);
			marginV = toIntPx(ctx, 20) / 2;
			textMargin = toIntPx(ctx, 5);
			configure(ctx, getResources().getConfiguration());

			var adapter = new AppListAdapter();
			ItemTouchHelper h = new ItemTouchHelper(adapter.getItemTouchCallback());
			setAdapter(adapter);
			h.attachToRecyclerView(this);
		}

		@Override
		protected void onConfigurationChanged(Configuration newConfig) {
			super.onConfigurationChanged(newConfig);
			configure(getContext(), newConfig);
		}

		private Drawable loadIcon(@DrawableRes int id) {
			var color =
					MaterialColors.getColor(getContext(),
							com.google.android.material.R.attr.colorOnSecondary,
							0);
			var icon = ResourcesCompat.getDrawable(getResources(), id, getContext().getTheme());
			assert icon != null;
			icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
			return icon;
		}

		private List<AppInfo> loadAppList() {
			var selectedApps = MainActivityPrefs.get().getStringArrayPref(AA_LAUNCHER_APPS);
			var apps = new ArrayList<AppInfo>(selectedApps.length + 1);
			var pm = getContext().getPackageManager();
			var userManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
			var allApps = loadAllAppList(pm);
			var addApp = AppInfo.ADD.pkg + '#' + AppInfo.ADD.name;
			var exitApp = AppInfo.EXIT.pkg + '#' + AppInfo.EXIT.name;

			for (var app : selectedApps) {
				// Parse stored format: "package#activity" or "package#activity#userSerialNumber"
				var parts = app.split("#");
				var pkg = parts.length > 0 ? parts[0] : "";
				var activityName = parts.length > 1 ? parts[1] : "";
				var userSerialNumber = parts.length > 2 ? Long.parseLong(parts[2]) : -1L;

				if (app.equals(addApp) || (pkg.equals(AppInfo.ADD.pkg) && activityName.equals(AppInfo.ADD.name))) {
					apps.add(AppInfo.ADD);
					addApp = null;
					continue;
				}
				if (app.equals(exitApp) || (pkg.equals(AppInfo.EXIT.pkg) && activityName.equals(AppInfo.EXIT.name))) {
					apps.add(AppInfo.EXIT);
					continue;
				}

				for (var appProfileInfo : allApps) {
					var info = appProfileInfo.info;
					var infoPkg = info.activityInfo.packageName;
					var infoActivity = info.activityInfo.name;
					var infoUserHandle = appProfileInfo.userHandle;

					// Get user serial number for comparison
					long infoSerialNumber = -1L;
					if (userManager != null) {
						infoSerialNumber = userManager.getSerialNumberForUser(infoUserHandle);
					}

					// Match by package, activity, and user profile
					boolean matches = pkg.equals(infoPkg) && activityName.equals(infoActivity);
					if (userSerialNumber != -1L) {
						matches = matches && (userSerialNumber == infoSerialNumber);
					}

					if (matches) {
						apps.add(new AppInfo(info.activityInfo.packageName, info.activityInfo.name,
								info.loadLabel(pm).toString(), info.loadIcon(pm), infoUserHandle));
						break;
					}
				}
			}

			if (addApp != null) apps.add(AppInfo.ADD);
			return apps;
		}

		private static class AppProfileInfo {
			final ResolveInfo info;
			final UserHandle userHandle;

			AppProfileInfo(ResolveInfo info, UserHandle userHandle) {
				this.info = info;
				this.userHandle = userHandle;
			}
		}

		private List<AppProfileInfo> loadAllAppList(PackageManager pm) {
			var launcherApps = (LauncherApps) getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);
			var userManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
			var allApps = new ArrayList<AppProfileInfo>();

			if (launcherApps != null && userManager != null) {
				var profiles = userManager.getUserProfiles();
				for (var profile : profiles) {
					try {
						var activities = launcherApps.getActivityList(null, profile);
						for (var activityInfo : activities) {
							var intent = new Intent(Intent.ACTION_MAIN);
							intent.addCategory(Intent.CATEGORY_LAUNCHER);
							intent.setComponent(activityInfo.getComponentName());
							var resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
							if (!resolveInfos.isEmpty()) {
								allApps.add(new AppProfileInfo(resolveInfos.get(0), profile));
							}
						}
					} catch (Exception e) {
						// Profile might not be accessible, skip it
					}
				}
			}

			// Fallback to main profile only if LauncherApps is not available
			if (allApps.isEmpty()) {
				var intent = new Intent(Intent.ACTION_MAIN);
				intent.addCategory(Intent.CATEGORY_LAUNCHER);
				var resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
				for (var info : resolveInfos) {
					allApps.add(new AppProfileInfo(info, Process.myUserHandle()));
				}
			}

			return allApps;
		}

		@SuppressLint("NotifyDataSetChanged")
		private void selectApps() {
			for (var app : selectApps) {
				if (app.selected) {
					if (!apps.contains(app)) {
						var idx = apps.size();
						if (apps.get(idx - 1).equals(AppInfo.ADD)) idx -= 1;
						var ai = new AppInfo(app.pkg, app.name, app.label, app.icon(), app.userHandle);
						apps.add(idx, AppInfo.EXIT.equals(ai) ? AppInfo.EXIT : ai);
					}
				} else {
					apps.remove(app);
				}
			}
			saveApps();
			selectApps = null;
			Objects.requireNonNull(getAdapter()).notifyDataSetChanged();
		}

		private void saveApps() {
			var userManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
			MainActivityPrefs.get().applyStringArrayPref(AA_LAUNCHER_APPS,
					CollectionUtils.mapToArray(apps, i -> {
						var result = i.pkg + '#' + i.name;
						if (i.userHandle != null && userManager != null) {
							var serialNumber = userManager.getSerialNumberForUser(i.userHandle);
							result += '#' + serialNumber;
						}
						return result;
					}, String[]::new));
		}

		private void configure(Context ctx, Configuration cfg) {
			var span = Math.max(cfg.screenWidthDp / 96, 2);
			var width = toPx(ctx, cfg.screenWidthDp);
			var margin = width * 0.3f / (span + 1);
			iconSize = (int) ((width / span) - margin);
			marginH = (int) (margin / 2);
			var lp = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
			lp.setMargins(marginH, marginV, marginH, marginV);
			setLayoutParams(lp);
			setLayoutManager(new GridLayoutManager(ctx, span));
		}

		private static class AppInfo {
			private static final AppInfo ADD =
					new AppInfo(FermataApplication.get().getPackageName(), "add", null, null, null);
			private static final AppInfo BACK =
					new AppInfo(FermataApplication.get().getPackageName(), "back", null, null, null);
			private static final AppInfo EXIT =
					new AppInfo(FermataApplication.get().getPackageName(), "exit", null, null, null);
			final String pkg;
			final String name;
			final String label;
			final UserHandle userHandle;
			protected Drawable icon;

			private AppInfo(String pkg, String name, String label, Drawable icon, UserHandle userHandle) {
				this.pkg = pkg;
				this.name = name;
				this.label = label;
				this.icon = icon;
				this.userHandle = userHandle;
			}

			/**
			 * @noinspection EqualsWhichDoesntCheckParameterClass
			 */
			@Override
			public boolean equals(Object o) {
				if (this == o) return true;
				AppInfo appInfo = (AppInfo) o;
				return Objects.equals(pkg, appInfo.pkg) && Objects.equals(name, appInfo.name) &&
						Objects.equals(userHandle, appInfo.userHandle);
			}

			@Override
			public int hashCode() {
				return Objects.hash(pkg, name, userHandle);
			}

			public Drawable icon() {return icon;}
		}

		private static final class SelectAppInfo extends AppInfo implements Comparable<SelectAppInfo> {
			private final PackageManager pm;
			private final ResolveInfo info;
			boolean selected;

			private SelectAppInfo(PackageManager pm, ResolveInfo info, UserHandle userHandle) {
				this(pm, info, info.activityInfo.packageName, info.activityInfo.name,
						info.loadLabel(pm).toString(), null, userHandle);
			}

			private SelectAppInfo(PackageManager pm, ResolveInfo info, String pkg, String name,
														String label, Drawable icon, UserHandle userHandle) {
				super(pkg, name, label, icon, userHandle);
				this.pm = pm;
				this.info = info;
			}

			@Override
			public Drawable icon() {
				if (icon == null) icon = info.loadIcon(pm);
				return icon;
			}

			@Override
			public int compareTo(SelectAppInfo o) {
				return label.compareTo(o.label);
			}
		}

		private final class AppView extends LinearLayoutCompat implements OnClickListener {
			private final AppCompatImageView icon;
			private final MaterialTextView text;
			private AppInfo appInfo;

			public AppView(Context ctx) {
				super(ctx);
				setOnClickListener(this);
				setOrientation(VERTICAL);
				setLayoutParams(new GridLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
				addView(icon = new AppCompatImageView(ctx));
				addView(text = new MaterialTextView(ctx));

				var lp = new LinearLayoutCompat.LayoutParams(iconSize, iconSize);
				lp.gravity = Gravity.CENTER;
				icon.setLayoutParams(lp);
				icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

				lp = new LinearLayoutCompat.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
				lp.gravity = Gravity.CENTER;
				text.setLayoutParams(lp);
				text.setMaxLines(1);
				text.setGravity(Gravity.CENTER);
				text.setEllipsize(TextUtils.TruncateAt.END);
				text.setCompoundDrawablePadding(toIntPx(getContext(), 5));
			}

			void setAppInfo(AppInfo appInfo) {
				this.appInfo = appInfo;

				if (appInfo == AppInfo.ADD) {
					icon.setImageDrawable(addIcon);
					text.setVisibility(GONE);
				} else if (appInfo == AppInfo.BACK) {
					if (backIcon == null) backIcon = loadIcon(R.drawable.back_circle);
					icon.setImageDrawable(backIcon);
					text.setVisibility(GONE);
				} else {
					text.setVisibility(VISIBLE);
					if (appInfo == AppInfo.EXIT) {
						if (exitIcon == null) exitIcon = loadIcon(R.drawable.shutdown);
						icon.setImageDrawable(exitIcon);
						text.setText(getContext().getString(R.string.exit));
					} else {
						icon.setImageDrawable(appInfo.icon());
						text.setText(appInfo.label);
					}
					if (appInfo instanceof SelectAppInfo sai) {
						Drawable icon;
						if (sai.selected) {
							if (checkedIcon == null) checkedIcon = loadIcon(me.aap.utils.R.drawable.check_box);
							icon = checkedIcon;
						} else {
							if (uncheckedIcon == null)
								uncheckedIcon = loadIcon(me.aap.utils.R.drawable.check_box_blank);
							icon = uncheckedIcon;
						}
						text.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
					} else {
						text.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
					}
				}

				var lp = (LinearLayoutCompat.LayoutParams) icon.getLayoutParams();
				lp.height = lp.width = iconSize;

				if (text.getVisibility() == GONE) {
					var bounds = new Rect();
					var s = getResources().getText(R.string.app_name).toString();
					text.getPaint().getTextBounds(s, 0, s.length(), bounds);
					var pad = bounds.height() / 2;
					lp.setMargins(marginH, marginV + pad, marginH, marginV + pad);
				} else {
					lp.setMargins(marginH, marginV, marginH, 0);
					lp = (LinearLayoutCompat.LayoutParams) text.getLayoutParams();
					lp.setMargins(0, textMargin, 0, marginV);
				}
			}

			@SuppressLint("NotifyDataSetChanged")
			@Override
			public void onClick(View v) {
				if (appInfo instanceof SelectAppInfo sai) {
					sai.selected = !sai.selected;
					setAppInfo(sai);
					return;
				}

				icon.startAnimation(animation);

				if (appInfo.equals(AppInfo.EXIT)) {
					MirrorDisplay.close();
				} else if (appInfo.equals(AppInfo.ADD)) {
					var pm = getContext().getPackageManager();
					var allApps = loadAllAppList(pm);
					if (exitIcon == null) exitIcon = loadIcon(R.drawable.shutdown);
					selectApps = new SelectAppInfo[allApps.size() + 1];
					selectApps[0] = new SelectAppInfo(null, null, AppInfo.EXIT.pkg, AppInfo.EXIT.name,
							getContext().getString(R.string.exit), exitIcon, null);
					for (int i = 1; i < selectApps.length; i++) {
						var appProfileInfo = allApps.get(i - 1);
						selectApps[i] = new SelectAppInfo(pm, appProfileInfo.info, appProfileInfo.userHandle);
					}
					Arrays.sort(selectApps, 1, selectApps.length);
					for (var app : selectApps) app.selected = AppListView.this.apps.contains(app);
					Objects.requireNonNull(getAdapter()).notifyDataSetChanged();
				} else if (appInfo.equals(AppInfo.BACK)) {
					selectApps();
				} else {
					try {
						var launcherApps = (LauncherApps) getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);

						// Use LauncherApps API if available and userHandle is set
						if (launcherApps != null && appInfo.userHandle != null) {
							var component = new ComponentName(appInfo.pkg, appInfo.name);
							var extras = new android.os.Bundle();
							extras.putInt(INTENT_EXTRA_MODE, FermataApplication.get().getMirroringMode());
							launcherApps.startMainActivity(component, appInfo.userHandle, null, extras);
						} else {
							// Fallback to traditional method for main profile
							var intent = new Intent(Intent.ACTION_MAIN);
							intent.addCategory(Intent.CATEGORY_LAUNCHER);
							intent.setClassName(appInfo.pkg, appInfo.name);
							intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
							intent.putExtra(INTENT_EXTRA_MODE, FermataApplication.get().getMirroringMode());
							getContext().startActivity(intent);
						}
						MirrorDisplay.disableAccelRotation();
					} catch (Exception err) {
						Toast.makeText(getContext(), err.getLocalizedMessage(), Toast.LENGTH_LONG).show();
					}
				}
			}
		}

		private static final class AppViewHolder extends RecyclerView.ViewHolder {

			public AppViewHolder(@NonNull View itemView) {
				super(itemView);
			}
		}

		private final class AppListAdapter extends MovableRecyclerViewAdapter<AppViewHolder> {

			@NonNull
			@Override
			public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				return new AppViewHolder(new AppView(getContext()));
			}

			@Override
			public void onBindViewHolder(@NonNull AppViewHolder holder, int pos) {
				var v = (AppView) holder.itemView;
				v.setAppInfo(
						(selectApps == null) ? apps.get(pos) : (pos == 0) ? AppInfo.BACK :
								selectApps[pos - 1]);
			}

			@Override
			public int getItemCount() {
				return (selectApps == null) ? apps.size() : selectApps.length + 1;
			}

			@Override
			protected void onItemDismiss(int position) {}

			@Override
			protected boolean onItemMove(int fromPosition, int toPosition) {
				if (selectApps != null) return false;
				move(apps, fromPosition, toPosition);
				saveApps();
				return true;
			}

			@Override
			protected boolean isLongPressDragEnabled() {
				return selectApps == null;
			}

			@Override
			protected boolean isItemViewSwipeEnabled() {
				return false;
			}
		}
	}
}
