/*
 * SkyTube
 * Copyright (C) 2015  Ramon Mifsud
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package free.rm.skytube.gui.activities;

import android.app.ProgressDialog;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.FileProvider;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

import butterknife.Bind;
import butterknife.ButterKnife;
import free.rm.skytube.R;
import free.rm.skytube.businessobjects.AsyncTaskParallel;
import free.rm.skytube.businessobjects.MainActivityListener;
import free.rm.skytube.businessobjects.YouTubeChannel;
import free.rm.skytube.gui.businessobjects.UpdatesChecker;
import free.rm.skytube.gui.businessobjects.WebStream;
import free.rm.skytube.gui.businessobjects.YouTubePlayer;
import free.rm.skytube.gui.fragments.ChannelBrowserFragment;
import free.rm.skytube.gui.fragments.MainFragment;
import free.rm.skytube.gui.fragments.SearchVideoGridFragment;

/**
 * Main activity (launcher).  This activity holds {@link free.rm.skytube.gui.fragments.VideosGridFragment}.
 */
public class MainActivity extends AppCompatActivity implements MainActivityListener {
	@Bind(R.id.fragment_container)
	protected FrameLayout fragmentContainer;

	private MainFragment mainFragment;
	private SearchVideoGridFragment searchVideoGridFragment;

	/** Set to true of the UpdatesCheckerTask has run; false otherwise. */
	private static boolean updatesCheckerTaskRan = false;

	private static final String MAIN_FRAGMENT   = "MainActivity.MainFragment";
	private static final String SEARCH_FRAGMENT = "MainActivity.SearchFragment";


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// check for updates (one time only)
		if (!updatesCheckerTaskRan)
			new UpdatesCheckerTask().executeInParallel();

		setContentView(R.layout.activity_main);
		ButterKnife.bind(this);

		if(fragmentContainer != null) {
			if(savedInstanceState != null) {
				mainFragment = (MainFragment)getSupportFragmentManager().getFragment(savedInstanceState, MAIN_FRAGMENT);
				searchVideoGridFragment = (SearchVideoGridFragment) getSupportFragmentManager().getFragment(savedInstanceState, SEARCH_FRAGMENT);
			}

			if(mainFragment == null) {
				mainFragment = new MainFragment();
				getSupportFragmentManager().beginTransaction().add(R.id.fragment_container, mainFragment).commit();
			}
		}
	}


	@Override
	protected void onSaveInstanceState(Bundle outState) {
		if(mainFragment != null)
			getSupportFragmentManager().putFragment(outState, MAIN_FRAGMENT, mainFragment);
		if(searchVideoGridFragment != null && searchVideoGridFragment.isVisible())
			getSupportFragmentManager().putFragment(outState, SEARCH_FRAGMENT, searchVideoGridFragment);
		super.onSaveInstanceState(outState);
	}


	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_activity_menu, menu);

		// setup the SearchView (actionbar)
		final MenuItem searchItem = menu.findItem(R.id.menu_search);
		final SearchView searchView = (SearchView) MenuItemCompat.getActionView(searchItem);

		searchView.setQueryHint(getString(R.string.search_videos));
		searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
			@Override
			public boolean onQueryTextChange(String newText) {
				return false;
			}

			@Override
			public boolean onQueryTextSubmit(String query) {
				// hide the keyboard
				searchView.clearFocus();

				// open SearchVideoGridFragment and display the results
				searchVideoGridFragment = new SearchVideoGridFragment();
				Bundle bundle = new Bundle();
				bundle.putString(SearchVideoGridFragment.QUERY, query);
				searchVideoGridFragment.setArguments(bundle);
				switchToFragment(searchVideoGridFragment);

				return true;
			}
		});

		return true;
	}


	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_preferences:
				Intent i = new Intent(this, PreferencesActivity.class);
				startActivity(i);
				return true;
			case R.id.menu_enter_video_url:
				displayEnterVideoUrlDialog();
				return true;
			case android.R.id.home:
				if(mainFragment == null || !mainFragment.isVisible()) {
					onBackPressed();
					return true;
				}
		}

		return super.onOptionsItemSelected(item);
	}


	/**
	 * Display the Enter Video URL dialog.
	 */
	private void displayEnterVideoUrlDialog() {
		final AlertDialog alertDialog = new AlertDialog.Builder(this)
			.setView(R.layout.dialog_enter_video_url)
			.setTitle(R.string.enter_video_url)
			.setPositiveButton(R.string.play, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					// get the inputted URL string
					final String videoUrl = ((EditText)((AlertDialog) dialog).findViewById(R.id.dialog_url_edittext)).getText().toString();

					// play the video
					YouTubePlayer.launch(videoUrl, MainActivity.this);
				}
			})
			.setNegativeButton(R.string.cancel, null)
			.show();

		// paste whatever there is in the clipboard (hopefully it is a video url)
		((EditText) alertDialog.findViewById(R.id.dialog_url_edittext)).setText(getClipboardItem());

		// clear URL edittext button
		alertDialog.findViewById(R.id.dialog_url_clear_button).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				((EditText) alertDialog.findViewById(R.id.dialog_url_edittext)).setText("");
			}
		});
	}


	/**
	 * Return the last item stored in the clipboard.
	 *
	 * @return	{@link String}
	 */
	private String getClipboardItem() {
		String item = "";

		ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		if (clipboard.hasPrimaryClip()) {
			android.content.ClipDescription description = clipboard.getPrimaryClipDescription();
			android.content.ClipData data = clipboard.getPrimaryClip();
			if (data != null && description != null && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
				item = String.valueOf(data.getItemAt(0).getText());
		}

		return item;
	}


	@Override
	public void onBackPressed() {
		if (mainFragment != null  &&  mainFragment.isVisible()) {
			// On Android, when the user presses back button, the Activity is destroyed and will be
			// recreated when the user relaunches the app.
			// We do not want that behaviour, instead then the back button is pressed, the app will
			// be **minimized**.
			Intent startMain = new Intent(Intent.ACTION_MAIN);
			startMain.addCategory(Intent.CATEGORY_HOME);
			startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(startMain);
		} else {
			super.onBackPressed();
		}
	}


	private void switchToFragment(Fragment fragment) {
		FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

		transaction.replace(R.id.fragment_container, fragment);
		transaction.addToBackStack(null);
		transaction.commit();
	}



	@Override
	public void onChannelClick(YouTubeChannel channel) {
		Intent i = new Intent(MainActivity.this, FragmentHolderActivity.class);
		i.putExtra(FragmentHolderActivity.FRAGMENT_HOLDER_CHANNEL_BROWSER, true);
		i.putExtra(ChannelBrowserFragment.CHANNEL_OBJ, channel);
		startActivity(i);
	}



	@Override
	public void onChannelClick(String channelId) {
		Intent i = new Intent(MainActivity.this, FragmentHolderActivity.class);
		i.putExtra(FragmentHolderActivity.FRAGMENT_HOLDER_CHANNEL_BROWSER, true);
		i.putExtra(ChannelBrowserFragment.CHANNEL_ID, channelId);
		startActivity(i);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////


	/**
	 * A task that will check if any SkyTube updates have been published.  If there are, then the
	 * user will be notified.
	 */
	private class UpdatesCheckerTask extends AsyncTaskParallel<Void, Void, UpdatesChecker> {

		@Override
		protected UpdatesChecker doInBackground(Void... params) {
			UpdatesChecker updatesChecker = new UpdatesChecker();
			updatesChecker.checkForUpdates();
			return updatesChecker;
		}

		@Override
		protected void onPostExecute(final UpdatesChecker updatesChecker) {
			updatesCheckerTaskRan = true;

			if (updatesChecker != null && updatesChecker.getLatestApkUrl() != null) {
				new AlertDialog.Builder(MainActivity.this)
								.setTitle(R.string.update_available)
								.setMessage( String.format(getResources().getString(R.string.update_dialog_msg), Float.toString(updatesChecker.getLatestApkVersion())) )
								.setPositiveButton(R.string.update, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog, int which) {
										new UpgradeAppTask(updatesChecker.getLatestApkUrl()).executeInParallel();
									}
								})
								.setNegativeButton(R.string.later, null)
								.show();
			}
		}

	}



	/**
	 * This task will download the remote APK file and it will install it for the user (provided that
	 * the user accepts such installation).
	 */
	private class UpgradeAppTask extends AsyncTaskParallel<Void, Integer, Pair<File, Throwable>> {

		private URL apkUrl;
		private ProgressDialog downloadDialog;

		/** The directory where the apks are downloaded to. */
		private final File apkDir = getCacheDir();
		private final String TAG = UpgradeAppTask.class.getSimpleName();


		public UpgradeAppTask(URL apkUrl) {
			this.apkUrl = apkUrl;
		}


		@Override
		protected void onPreExecute() {
			// setup the download dialog and display it
			downloadDialog = new ProgressDialog(MainActivity.this);
			downloadDialog.setMessage(getString(R.string.downloading));
			downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			downloadDialog.setProgress(0);
			downloadDialog.setMax(100);
			downloadDialog.setCancelable(false);
			downloadDialog.setProgressNumberFormat(null);
			downloadDialog.show();
		}

		@Override
		protected Pair<File, Throwable> doInBackground(Void... params) {
			File		apkFile;
			Throwable	exception = null;

			// delete old apk files
			deleteOldApkFiles();

			// try to download the remote APK file
			try {
				apkFile = downloadApk();
			} catch (Throwable e) {
				apkFile = null;
				exception = e;
			}

			return new Pair<>(apkFile, exception);
		}


		/**
		 * Delete old (previously-downloaded) APK files.
		 */
		private void deleteOldApkFiles() {
			// get all previously downloaded APK files
			File[] apkFiles = apkDir.listFiles(new FilenameFilter() {
				public boolean accept(File dir, String filename) {
					return filename.endsWith(".apk");
				}
			} );

			// delete the previously downloaded APK files
			if (apkFiles != null) {
				for (File apkFile : apkFiles) {
					if (apkFile.delete()) {
						Log.i(TAG, "Deleted " + apkFile.getAbsolutePath());
					} else {
						Log.e(TAG, "Cannot delete " + apkFile.getAbsolutePath());
					}
				}
			}
		}


		/**
		 * Download the remote APK file and return an instance of {@link File}.
		 *
		 * @return	A {@link File} instance of the downloaded APK.
		 * @throws IOException
		 */
		private File downloadApk() throws IOException {
			WebStream       webStream = new WebStream(this.apkUrl);
			File			apkFile = File.createTempFile("skytube-upgrade", ".apk", apkDir);
			OutputStream    out;

			// set the APK file to readable to every user so that this file can be read by Android's
			// package manager program
			apkFile.setReadable(true /*set file to readable*/, false /*set readable to every user on the system*/);
			out = new FileOutputStream(apkFile);

			// download the file by transferring bytes from in to out
			byte[]	buf = new byte[1024];
			int		totalBytesRead = 0;
			for (int bytesRead; (bytesRead = webStream.getStream().read(buf)) > 0; ) {
				out.write(buf, 0, bytesRead);

				// update the progressbar of the downloadDialog
				totalBytesRead += bytesRead;
				publishProgress(totalBytesRead, webStream.getStreamSize());
			}

			// close the streams
			webStream.getStream().close();
			out.close();

			return apkFile;
		}


		@Override
		protected void onProgressUpdate(Integer... values) {
			float	totalBytesRead = values[0];
			float	fileSize = values[1];
			float	percentageDownloaded = (totalBytesRead / fileSize) * 100f;

			downloadDialog.setProgress((int)percentageDownloaded);
		}


		@Override
		protected void onPostExecute(Pair<File, Throwable> out) {
			File		apkFile   = out.first;
			Throwable	exception = out.second;

			// hide the download dialog
			downloadDialog.dismiss();

			if (exception != null) {
				Log.e(TAG, "Unable to upgrade app", exception);
				Toast.makeText(MainActivity.this, R.string.update_failure, Toast.LENGTH_LONG).show();
			} else {
				displayUpgradeAppDialog(apkFile);
			}
		}


		/**
		 * Ask the user whether he wants to install the latest SkyTube's APK file.
		 *
		 * @param apkFile	APK file to install.
		 */
		private void displayUpgradeAppDialog(File apkFile) {
			Context context = getBaseContext();
			Uri     apkFileURI = (android.os.Build.VERSION.SDK_INT >= 24)
						? FileProvider.getUriForFile(context, context.getApplicationContext().getPackageName() + ".provider", apkFile)  // we now need to call FileProvider.getUriForFile() due to security changes in Android 7.0+
						: Uri.fromFile(apkFile);
			Intent  intent = new Intent(Intent.ACTION_VIEW);

			intent.setDataAndType(apkFileURI, "application/vnd.android.package-archive");
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK /* asks the user to open the newly updated app */
							| Intent.FLAG_GRANT_READ_URI_PERMISSION /* to avoid a crash due to security changes in Android 7.0+ */);
			startActivity(intent);
		}

	}
}
