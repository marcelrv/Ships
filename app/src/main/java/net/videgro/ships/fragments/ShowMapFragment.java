package net.videgro.ships.fragments;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ShareActionProvider;
import android.widget.ShareActionProvider.OnShareTargetSelectedListener;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.google.gson.Gson;

import net.videgro.ships.Analytics;
import net.videgro.ships.R;
import net.videgro.ships.SettingsUtils;
import net.videgro.ships.Utils;
import net.videgro.ships.fragments.internal.FragmentUtils;
import net.videgro.ships.listeners.ImagePopupListener;
import net.videgro.ships.listeners.NmeaReceivedListener;
import net.videgro.ships.listeners.OwnLocationReceivedListener;
import net.videgro.ships.nmea2ship.Nmea2Ship;
import net.videgro.ships.nmea2ship.domain.Ship;
import net.videgro.ships.services.NmeaUdpClientService;
import net.videgro.ships.services.TrackService;
import net.videgro.ships.tools.HttpCacheTileServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.OnNeverAskAgain;
import permissions.dispatcher.OnPermissionDenied;
import permissions.dispatcher.OnShowRationale;
import permissions.dispatcher.PermissionRequest;
import permissions.dispatcher.RuntimePermissions;

@RuntimePermissions
public class ShowMapFragment extends Fragment implements OwnLocationReceivedListener, NmeaReceivedListener, ImagePopupListener {
    private static final String TAG = "ShowMapFragment";

    private static final DecimalFormat GPS_COORD_FORMAT = new DecimalFormat("##.00");
    private static final String FILE_MAP = "ships_map.jpg";

    private static final int IMAGE_POPUP_ID_CALIBRATE_WARNING = 1101;
    private static final int IMAGE_POPUP_ID_OPEN_RTLSDR_ERROR = 1102;
    private static final int IMAGE_POPUP_ID_IGNORE = 1103;

    private static final int REQ_CODE_START_RTLSDR = 1201;
    private static final int REQ_CODE_STOP_RTLSDR = 1202;

    /**
     * The value of this placeholder is used literally in the webview link (ship popup) and in the string resources "url_mmsi_info".
     * Respectively indicating that this is a special URL and must be opened in a new browser and as a placeholder to be replaced by the real MMSI value.
     */
    private static final String PLACEHOLDER_MMSI = "PLACEHOLDER_MMSI";

    private WebView webView;
    private TextView logTextView;
    private Nmea2Ship nmea2Ship = new Nmea2Ship();
    private TrackService trackService;
    private NmeaUdpClientService nmeaUdpClientService;
    private ServiceConnection locationServiceConnection;
    private ServiceConnection nmeaUdpClientServiceConnection;
    private Location lastReceivedOwnLocation = null;
    private ToggleButton startStopButton;
    private File fileMap;

    /**
     * Contains all received MMSIs. A set contains unique entries.
     */
    private Set<Integer> mmsiReceived = new HashSet<Integer>();

    @SuppressLint("NewApi")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final View rootView = inflater.inflate(R.layout.fragment_map, container, false);

        logTextView = (TextView) rootView.findViewById(R.id.textView1);

        final File filesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        fileMap = new File(filesDirectory, FILE_MAP);

        Utils.loadAd(rootView);
        setHasOptionsMenu(true);
        setupWebView(rootView);
        setupNmeaUdpClientService();

        startStopButton = (ToggleButton) rootView.findViewById(R.id.startStopAisButton);
        startStopButton.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startReceivingAisFromAntenna();
                } else {
                    stopReceivingAisFromAntenna();
                }
            }
        });

        // TODO: Not possible to stop. Will start automatically when valid PPM exists and hide (stop) button for now
        startStopButton.setVisibility(View.GONE);
        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();

        final int ppm = SettingsUtils.parseFromPreferencesRtlSdrPpm(this.getActivity());
        if (!SettingsUtils.isValidPpm(ppm)) {
            Utils.showPopup(IMAGE_POPUP_ID_CALIBRATE_WARNING, this.getActivity(), this, getString(R.string.popup_no_ppm_set_title), getString(R.string.popup_no_ppm_set_message), R.drawable.warning_icon, null);
        } else {
            // Start tiles caching server, will also load the OpenStreetMap after server has started
            ShowMapFragmentPermissionsDispatcher.setupHttpCachingTileServerWithCheck(this);

            ShowMapFragmentPermissionsDispatcher.setupLocationServiceWithCheck(this);
            startReceivingAisFromAntenna();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        Analytics.logScreenView(getActivity(), TAG);
    }

    @Override
    public void onStop() {
        final String tag = "onStop";

        if (mmsiReceived.size() == 0) {
            Analytics.logEvent(getActivity(), Analytics.CATEGORY_STATISTICS,"No ships received.",Utils.retrieveAbi());
        } else {
            Analytics.logEvent(getActivity(), Analytics.CATEGORY_STATISTICS, "Number of received ships",new DecimalFormat("000000").format(mmsiReceived.size()));
        }

        Analytics.logEvent(getActivity(), Analytics.CATEGORY_STATISTICS, "HttpCacheTileServer",HttpCacheTileServer.getInstance().getStatistics());

        super.onStop();
    }

    @Override
    public void onDestroy() {
        destroyNmeaUdpClientService();
        destroyLocationService();
        super.onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        Log.i(TAG, "onActivityResult requestCode: " + requestCode + ", resultCode: " + resultCode);

        switch (requestCode) {
            case REQ_CODE_START_RTLSDR:
                final String startRtlSdrResultAsString = FragmentUtils.parseOpenCloseDeviceActivityResultAsString(data);
                Analytics.logEvent(getActivity(), Analytics.CATEGORY_STATISTICS, "OpenDeviceResult", startRtlSdrResultAsString+" - "+Utils.retrieveAbi());
                logStatus(startRtlSdrResultAsString);

                if (resultCode != Activity.RESULT_OK) {
                    resetGuiToInitialState();
                    Utils.showPopup(IMAGE_POPUP_ID_OPEN_RTLSDR_ERROR, getActivity(), this, getString(R.string.popup_start_device_failed_title), getString(R.string.popup_start_device_failed_message) + " " + startRtlSdrResultAsString, R.drawable.thumbs_down_circle, null);
                } else {
                    FragmentUtils.rtlSdrRunning = true;
                }
                break;
            case REQ_CODE_STOP_RTLSDR:
                logStatus(FragmentUtils.parseOpenCloseDeviceActivityResultAsString(data));
                FragmentUtils.rtlSdrRunning = false;
                break;

            default:
                Log.e(TAG, "Unexpected request code: " + requestCode);
        }
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    // Must be public to use PermissionsDispatcher
    public void setupHttpCachingTileServer() {
        final String tag = "setupHttpCachingTileServer - ";
        final HttpCacheTileServer httpCacheTileServer = HttpCacheTileServer.getInstance();
        httpCacheTileServer.init(getActivity(), SettingsUtils.parseFromPreferencesMapCacheDiskUsageMax(getActivity()));

        if (httpCacheTileServer.startServer()){
            // Server in place, load page

            final String url="file:///android_asset/index.html";
            if (webView.getUrl()==null || webView.getUrl().isEmpty() || !webView.getUrl().equalsIgnoreCase(url)) {
                webView.loadUrl("file:///android_asset/index.html");
                // Flow will resume at: onPageFinished
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(final View rootView) {
        webView = (WebView) rootView.findViewById(R.id.webview);

        // Enable JavaScript on webview
        final WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccessFromFileURLs(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webView.loadUrl("javascript:setZoomToExtent(" + Boolean.toString(SettingsUtils.parseFromPreferencesMapZoomToExtend(getActivity())) + ")");
                webView.loadUrl("javascript:setPrefetchLowerZoomLevelsTiles(" + Boolean.toString(SettingsUtils.parseFromPreferencesMapCacheLowerZoomlevels(getActivity())) + ")");
                if (lastReceivedOwnLocation != null) {
                    webView.loadUrl("javascript:setCurrentPosition(" + lastReceivedOwnLocation.getLongitude() + "," + lastReceivedOwnLocation.getLatitude() + ")");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                boolean result = false;
                if (url != null && url.contains(PLACEHOLDER_MMSI)) {
                    final String mmsi = url.split(PLACEHOLDER_MMSI)[1];
                    final String newUrl = getString(R.string.url_mmsi_info).replace(PLACEHOLDER_MMSI, mmsi);
                    Analytics.logEvent(getActivity(), TAG, "shipinfo", mmsi);
                    view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(newUrl)));
                    result = true;
                }
                return result;
            }
        });
    }

    @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    // Must be public to use PermissionsDispatcher
    public void setupLocationService() {
        locationServiceConnection = new LocationServiceConnection((OwnLocationReceivedListener) this);
        Intent serviceIntent = new Intent(getActivity(), TrackService.class);
        getActivity().startService(serviceIntent);
        getActivity().bindService(new Intent(getActivity(), TrackService.class), locationServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupNmeaUdpClientService() {
        nmeaUdpClientServiceConnection = new NmeaUdpClientServiceConnection((NmeaReceivedListener) this);
        Intent serviceIntent = new Intent(getActivity(), NmeaUdpClientService.class);
        getActivity().startService(serviceIntent);
        getActivity().bindService(new Intent(getActivity(), NmeaUdpClientService.class), nmeaUdpClientServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void destroyLocationService() {
        if (trackService != null) {
            trackService.setListener(null);
        }

        if (locationServiceConnection != null) {
            getActivity().unbindService(locationServiceConnection);
            locationServiceConnection = null;
        }
    }

    private void destroyNmeaUdpClientService() {
        if (nmeaUdpClientService != null) {
            nmeaUdpClientService.removeListener(this);
        }

        if (nmeaUdpClientServiceConnection != null) {
            getActivity().unbindService(nmeaUdpClientServiceConnection);
            nmeaUdpClientServiceConnection = null;
        }
    }

    private void startReceivingAisFromAntenna() {
        final String tag = "startReceivingAisFromAntenna - ";
        if (!FragmentUtils.rtlSdrRunning) {
            final int ppm = SettingsUtils.parseFromPreferencesRtlSdrPpm(getActivity());
            if (SettingsUtils.isValidPpm(ppm)) {
                final boolean startResult = FragmentUtils.startReceivingAisFromAntenna(this, REQ_CODE_START_RTLSDR, ppm);
                logStatus((startResult ? "Requested" : "Failed") + " to receive AIS from antenna (PPM: " + ppm + ").");

                // On positive result: Will continue at onActivityResult (REQ_CODE_START_RTLSDR)
            } else {
                Log.e(TAG, tag + "Invalid PPM: " + ppm);
            }
        } else {
            logStatus("Receiving AIS already, continue.");
        }
    }

    private void stopReceivingAisFromAntenna() {
        FragmentUtils.stopReceivingAisFromAntenna(this, REQ_CODE_STOP_RTLSDR);
    }

    private void resetGuiToInitialState() {
        startStopButton.setChecked(false);
    }

    private void logStatus(final String status) {
        Utils.logStatus(getActivity(), logTextView, status);
    }

    private Intent getShareIntent() {
        final ArrayList<Uri> uris = new ArrayList<>();
        uris.add(FileProvider.getUriForFile(getActivity(),getActivity().getApplicationContext().getPackageName() + getString(R.string.dot_provider),fileMap));

        final Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject));
        shareIntent.putExtra(Intent.EXTRA_TEXT, "\n\n" + getString(R.string.share_text) + " " + getString(R.string.app_name) + " - " + getString(R.string.app_url));
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        shareIntent.setType("*/*");

        return shareIntent;
    }

    private void takeScreenShotWithCheck() {
        ShowMapFragmentPermissionsDispatcher.takeScreenShotWithCheck(this);
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    // Must be public to use PermissionsDispatcher
	public void takeScreenShot() {
		Log.i(TAG, "takeScreenShot");

		final Picture picture = webView.capturePicture();
		final Bitmap b = Bitmap.createBitmap(picture.getWidth(), picture.getHeight(), Bitmap.Config.ARGB_8888);
		final Canvas c = new Canvas(b);
		picture.draw(c);

		FileOutputStream fosScreenshot = null;
		try {
			fosScreenshot = new FileOutputStream(fileMap);

			if (fosScreenshot != null) {
				b.compress(Bitmap.CompressFormat.JPEG, 100, fosScreenshot);
				fosScreenshot.close();
				Log.i(TAG, "takeScreenShot >> Screenshot available at: " + fileMap.toString());
			}
		} catch (IOException  e) {
			Log.e(TAG, "takeScreenShot", e);
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		menu.setGroupVisible(R.id.main_menu_group_share, true);

		final ShareActionProvider shareActionProvider = (ShareActionProvider) menu.findItem(R.id.menu_share).getActionProvider();
		shareActionProvider.setShareIntent(getShareIntent());
		shareActionProvider.setOnShareTargetSelectedListener(new OnShareTargetSelectedListener() {
			@Override
			public boolean onShareTargetSelected(ShareActionProvider actionProvider, Intent intent) {
				Analytics.logEvent(getActivity(), TAG, "share",""+mmsiReceived.size());
                takeScreenShotWithCheck();
				return false;
			}
		});
	}

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        ShowMapFragmentPermissionsDispatcher.onRequestPermissionsResult(this,requestCode,grantResults);
    }

    /************************** LISTENER IMPLEMENTATIONS ******************/

	/**** START ImagePopupListener ****/
	
	@Override
	public void onImagePopupDispose(int id) {
		switch (id) {
		case IMAGE_POPUP_ID_CALIBRATE_WARNING:
			final String switchToFragmentResult = FragmentUtils.switchToFragment(getActivity(),new CalibrateFragment());
			if (!switchToFragmentResult.isEmpty()){
				Analytics.logEvent(getActivity(), TAG,"onImagePopupDispose - IMAGE_POPUP_ID_CALIBRATE_WARNING - switchToFragment - Error",switchToFragmentResult);
				FragmentUtils.stopApplication(this);
			}
		break;
		case IMAGE_POPUP_ID_OPEN_RTLSDR_ERROR:
			// TODO: Currently all errors are fatal, because we can't stop and restart the RTL-SDR dongle correctly
			FragmentUtils.stopApplication(this);
		break;		
		default:
			Log.d(TAG,"onImagePopupDispose - id: "+id);
		}
	}
	
	/**** END ImagePopupListener ****/
	
	/**** START NmeaReceivedListener ****/
	
	@Override
	public void onNmeaReceived(String nmea) {
		final String tag="onNmeaReceived - ";
		
		final Ship ship = nmea2Ship.onMessage(nmea);

		if (ship != null && ship.isValid()) {
			if (ship.isValid()){
				final String json = new Gson().toJson(ship);
				mmsiReceived.add(ship.getMmsi());
				
				final String shipIdent="MMSI: "+ ship.getMmsi() + (ship.getName() != null  && !ship.getName().isEmpty() ? " "+ship.getName() : "")+" Country: "+ship.getCountryName();
				logStatus("Ship location received ("+shipIdent+")");
	
				if (getActivity()!=null){
					getActivity().runOnUiThread(new Runnable() {
						public void run() {
							webView.loadUrl("javascript:onShipReceived('" + json + "')");
						}
					});
				} else {
					Log.e(TAG,tag+"Huh?");
				}
			} else {
				Log.w(TAG,tag+"Ship is invalid: "+ship.toString());
			}
		}
	}
	
	/**** END NmeaListener ****/
	
	/**** START OwnLocationReceivedListener ****/
	
	@Override
	public void onOwnLocationReceived(final Location location) {
		logStatus("Own location received: Lon: " + GPS_COORD_FORMAT.format(location.getLongitude()) + ", Lat: " + GPS_COORD_FORMAT.format(location.getLatitude()));
		lastReceivedOwnLocation=location;
				
		if (getActivity()!=null){
			getActivity().runOnUiThread(new Runnable() {
				public void run() {
					webView.loadUrl("javascript:setCurrentPosition(" + location.getLongitude() + "," + location.getLatitude() + ")");
				}
			});
		} else {
			Log.e(TAG,"Huh?");
		}
	}
	
	/**** END OwnLocationReceivedListener ****/
		
	
	/************************** PRIVATE CLASS IMPLEMENTATIONS ******************/
	
	private class LocationServiceConnection implements ServiceConnection {
		private final OwnLocationReceivedListener listener;

		LocationServiceConnection(OwnLocationReceivedListener listener) {
			this.listener = listener;
		}

		public void onServiceConnected(ComponentName className, IBinder service) {
			if (service instanceof TrackService.ServiceBinder) {
				trackService = ((TrackService.ServiceBinder) service).getService();
				trackService.setListener(listener);
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			trackService = null;
		}
	}
	
	private class NmeaUdpClientServiceConnection implements ServiceConnection {
		private final String tag="NmeaUdpClientServiceConnection - ";
		private final NmeaReceivedListener listener;

		NmeaUdpClientServiceConnection(NmeaReceivedListener listener) {
			this.listener = listener;
		}

		public void onServiceConnected(ComponentName className, IBinder service) {
			if (service instanceof NmeaUdpClientService.ServiceBinder) {
				Log.d(TAG,tag+"onServiceConnected");
				nmeaUdpClientService = ((NmeaUdpClientService.ServiceBinder) service).getService();
				nmeaUdpClientService.addListener(listener);
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			nmeaUdpClientService = null;
		}
	}

    /************************** PermissionsDispatcher IMPLEMENTATIONS ******************/

    @OnShowRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showRationaleForExternalStorage(final PermissionRequest request) {
        new AlertDialog.Builder(getActivity())
                .setMessage(R.string.permission_externalstorage_rationale)
                .setPositiveButton(R.string.button_allow, new DialogInterface.OnClickListener(){
                    @Override
                    public void onClick(DialogInterface arg0, int arg1) {
                        request.proceed();
                    }
                })
                .setNegativeButton(R.string.button_deny, new DialogInterface.OnClickListener(){
                    @Override
                    public void onClick(DialogInterface arg0, int arg1) {
                        request.cancel();
                    }
                })
                .show();
    }

    @OnPermissionDenied(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showDeniedForExternalStorage() {
        if (isAdded()) {
            Utils.showPopup(IMAGE_POPUP_ID_IGNORE, getActivity(), this, getString(R.string.permission_externalstorage_denied_title), getString(R.string.permission_externalstorage_denied), R.drawable.thumbs_down_circle, null);
        }
    }

    @OnNeverAskAgain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    void showNeverAskForExternalStorage() {
        if (isAdded()) {
            Utils.showPopup(IMAGE_POPUP_ID_IGNORE, getActivity(), this, getString(R.string.permission_externalstorage_denied_title), getString(R.string.permission_externalstorage_neverask), R.drawable.thumbs_down_circle, null);
        }
    }

    @OnShowRationale(Manifest.permission.ACCESS_FINE_LOCATION)
    void showRationaleForLocation(final PermissionRequest request) {
        new AlertDialog.Builder(getActivity())
                .setMessage(R.string.permission_location_rationale)
                .setPositiveButton(R.string.button_allow, new DialogInterface.OnClickListener(){
                    @Override
                    public void onClick(DialogInterface arg0, int arg1) {
                        request.proceed();
                    }
                })
                .setNegativeButton(R.string.button_deny, new DialogInterface.OnClickListener(){
                    @Override
                    public void onClick(DialogInterface arg0, int arg1) {
                        request.cancel();
                    }
                })
                .show();
    }

    @OnPermissionDenied(Manifest.permission.ACCESS_FINE_LOCATION)
    void showDeniedForLocation() {
        if (isAdded()) {
            Utils.showPopup(IMAGE_POPUP_ID_IGNORE, getActivity(), this, getString(R.string.permission_location_denied_title), getString(R.string.permission_location_denied), R.drawable.thumbs_down_circle, null);
        }
    }

    @OnNeverAskAgain(Manifest.permission.ACCESS_FINE_LOCATION)
    void showNeverAskForLocation() {
        if (isAdded()) {
            Utils.showPopup(IMAGE_POPUP_ID_IGNORE, getActivity(), this, getString(R.string.permission_location_denied_title), getString(R.string.permission_location_neverask), R.drawable.thumbs_down_circle, null);
        }
    }
}
