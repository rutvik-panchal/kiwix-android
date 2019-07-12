package org.kiwix.kiwixmobile.zim_manager.local_file_transfer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import butterknife.BindView;
import butterknife.ButterKnife;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.kiwix.kiwixmobile.KiwixApplication;
import org.kiwix.kiwixmobile.R;
import org.kiwix.kiwixmobile.utils.AlertDialogShower;
import org.kiwix.kiwixmobile.utils.KiwixDialog;
import org.kiwix.kiwixmobile.utils.SharedPreferenceUtil;

import java.util.ArrayList;

import javax.inject.Inject;

/**
 * Created by @Aditya-Sood as a part of GSoC 2019.
 *
 * This activity is the starting point for the module used for sharing zims between devices.
 *
 * The module is used for transferring ZIM files from one device to another, from within the
 * app. Two devices are connected to each other using WiFi Direct, followed by file transfer.
 *
 * The module uses this activity along with {@link DeviceListFragment} to manage connection
 * and file transfer between the devices.
 * */
public class LocalFileTransferActivity extends AppCompatActivity implements WifiP2pManager.ChannelListener, DeviceListFragment.DeviceActionListener {

  public static final String TAG = "LocalFileTransferActvty"; // Not a typo, 'Log' tags have a length upper limit of 25 characters
  public static final int REQUEST_ENABLE_WIFI_P2P = 1;
  public static final int REQUEST_ENABLE_LOCATION_SERVICES = 2;
  private static final int PERMISSION_REQUEST_CODE_COARSE_LOCATION = 1;
  private static final int PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS = 2;

  @Inject SharedPreferenceUtil sharedPreferenceUtil;
  @Inject AlertDialogShower alertDialogShower;

  @BindView(R.id.toolbar_local_file_transfer) Toolbar actionBar;

  private ArrayList<Uri> fileUriArrayList;  // For sender device, stores Uris of files to be transferred
  private Boolean fileSendingDevice = false;// Whether the device is the file sender or not


  /* Variables related to the WiFi P2P API */
  private boolean wifiP2pEnabled = false; // Whether WiFi has been enabled or not
  private boolean retryChannel = false;   // Whether channel has retried connecting previously

  private WifiP2pManager manager;         // Overall manager of Wifi p2p connections for the module
  private WifiP2pManager.Channel channel; // Connects the module to device's underlying Wifi p2p framework

  private final IntentFilter intentFilter = new IntentFilter(); // For specifying broadcasts (of the P2P API) that the module needs to respond to
  private BroadcastReceiver receiver = null; // For receiving the broadcasts given by above filter

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_local_file_transfer);
    KiwixApplication.getApplicationComponent().activityComponent()
        .activity(this)
        .build()
        .inject(this);
    ButterKnife.bind(this);

    /*
    * Presence of file Uris decides whether the device with the activity open is a sender or receiver:
    * - On the sender device, this activity is started from the app chooser post selection
    * of files to share in the Library
    * - On the receiver device, the activity is started directly from within the 'Get Content'
    * activity, without any file Uris
    * */
    Intent filesIntent = getIntent();
    fileUriArrayList = filesIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    if(fileUriArrayList != null && fileUriArrayList.size() > 0) {
      setDeviceAsFileSender();
    }

    setSupportActionBar(actionBar);
    actionBar.setNavigationIcon(R.drawable.ic_close_white_24dp);
    actionBar.setNavigationOnClickListener(new View.OnClickListener(){
      @Override
      public void onClick(View v) {
        closeLocalFileTransferActivity();
      }
    });


    /* Initialisations for using the WiFi P2P API */

    // Intents that the broadcast receiver will be responding to
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
    intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

    manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
    channel = manager.initialize(this, getMainLooper(), null);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.wifi_file_share_items, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if(item.getItemId() == R.id.menu_item_search_devices) {

      /* Permissions essential for this module */
      if(!checkCoarseLocationAccessPermission()) return true;

      if(!checkExternalStorageWritePermission()) return true;

      // Initiate discovery
      if(!isWifiP2pEnabled()) {
        requestEnableWifiP2pServices();
        return true;
      }

      if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isLocationServicesEnabled()) {
        requestEnableLocationServices();
        return true;
      }

      final DeviceListFragment deviceListFragment = (DeviceListFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_device_list);
      deviceListFragment.onInitiateDiscovery();
      deviceListFragment.performFieldInjection(sharedPreferenceUtil, alertDialogShower);
      manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
          showToast(LocalFileTransferActivity.this, R.string.discovery_initiated, Toast.LENGTH_SHORT);
        }

        @Override
        public void onFailure(int reason) {
          String errorMessage = getErrorMessage(reason);
          showToast(LocalFileTransferActivity.this, LocalFileTransferActivity.this.getString(R.string.discovery_failed, errorMessage), Toast.LENGTH_SHORT);
        }
      });
      return true;

    } else if(item.getItemId() == R.id.menu_item_cancel_search) {
      if(manager != null) {
        cancelSearch();
      }
      return true;

    } else {
      return super.onOptionsItemSelected(item);
    }
  }


  /* Helper methods used in the activity */
  public void setDeviceAsFileSender() {
    fileSendingDevice = true;
  }

  public boolean isFileSender() {
    return fileSendingDevice;
  }

  public @NonNull ArrayList<Uri> getFileUriArrayList() {
    return fileUriArrayList;
  }

  public void setWifiP2pEnabled(boolean wifiP2pEnabled) {
    this.wifiP2pEnabled = wifiP2pEnabled;
  }

  public boolean isWifiP2pEnabled() {
    return wifiP2pEnabled;
  }

  private String getErrorMessage(int reason) {
    switch (reason) {
      case WifiP2pManager.ERROR:           return getString(R.string.wifi_p2p_internal_error);
      case WifiP2pManager.BUSY:            return getString(R.string.wifi_p2p_framework_busy);
      case WifiP2pManager.P2P_UNSUPPORTED: return getString(R.string.wifi_p2p_unsupported);

      default: return (getString(R.string.wifi_p2p_unknown_error, reason));
    }
  }

  public void resetPeers() {
    DeviceListFragment deviceListFragment = (DeviceListFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_device_list);
    if(deviceListFragment != null) {
      deviceListFragment.clearPeers();
    }
  }

  public void resetData() {
    DeviceListFragment deviceListFragment = (DeviceListFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_device_list);
    if(deviceListFragment != null) {
      deviceListFragment.clearPeers();
    }
  }
  
  static void showToast(Context context, int stringResource, int duration) {
    showToast(context, context.getString(stringResource), duration);
  }

  static void showToast(Context context, String text, int duration) {
    Toast.makeText(context, text, duration).show();
  }


  /* From WifiP2pManager.ChannelListener interface */
  @Override
  public void onChannelDisconnected() {
    // Upon disconnection, retry one more time
    if(manager != null && !retryChannel) {
      showToast(this, R.string.channel_lost, Toast.LENGTH_LONG);
      resetData();
      retryChannel = true;
      manager.initialize(this, getMainLooper(), this);

    } else {
      showToast(this, R.string.severe_loss_error, Toast.LENGTH_LONG);
    }
  }


  /* From DeviceListFragment.DeviceActionListener interface */
  @Override
  public void cancelSearch() {

    if (manager != null) {
      final DeviceListFragment fragment = (DeviceListFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_device_list);

      if (fragment.getUserDevice() == null
          || fragment.getUserDevice().status == WifiP2pDevice.CONNECTED) {
        disconnect();

      } else if (fragment.getUserDevice().status == WifiP2pDevice.AVAILABLE
          || fragment.getUserDevice().status == WifiP2pDevice.INVITED) {

        manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {

          @Override
          public void onSuccess() {
            showToast(LocalFileTransferActivity.this, R.string.abort_connection,
                Toast.LENGTH_SHORT);
          }

          @Override
          public void onFailure(int reasonCode) {
            String errorMessage = getErrorMessage(reasonCode);
            showToast(LocalFileTransferActivity.this,
                getString(R.string.abort_failed, errorMessage),
                Toast.LENGTH_SHORT);
          }
        });
      }
    }
  }

  @Override
  public void connect(@NonNull final WifiP2pDevice peerDevice) {
    WifiP2pConfig config = new WifiP2pConfig();
    config.deviceAddress = peerDevice.deviceAddress;
    config.wps.setup = WpsInfo.PBC;

    manager.connect(channel, config, new WifiP2pManager.ActionListener() {
      @Override
      public void onSuccess() {
        // UI updated from broadcast receiver
      }

      @Override
      public void onFailure(int reason) {
        String errorMessage = getErrorMessage(reason);
        showToast(LocalFileTransferActivity.this, getString(R.string.connection_failed, errorMessage), Toast.LENGTH_LONG);
      }
    });
  }

  @Override
  public void closeLocalFileTransferActivity() {
    fileSendingDevice = false;
    disconnect();
    this.finish();
  }

  public void disconnect() {
    manager.removeGroup(channel, new WifiP2pManager.ActionListener() {

      @Override
      public void onFailure(int reasonCode) {
        Log.d(TAG, "Disconnect failed. Reason: " + reasonCode);
      }

      @Override
      public void onSuccess() {
        Log.d(TAG, "Disconnect successful");
      }

    });
  }

  /* Helper methods used in the activity */
  private boolean checkCoarseLocationAccessPermission() { // Required by Android to detect wifi-p2p peers
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

      if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

        if(shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
          alertDialogShower.show(KiwixDialog.LocationPermissionRationale.INSTANCE, new Function0<Unit>() {
            @Override public Unit invoke() {
              requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_CODE_COARSE_LOCATION);
              return Unit.INSTANCE;
            }
          });

        } else {
          requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_CODE_COARSE_LOCATION);
        }

        return false;
      }
    }

    return true; // Control reaches here: Either permission granted at install time, or at the time of request
  }

  private boolean checkExternalStorageWritePermission() { // To access and store the zims
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

      if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

        if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
          alertDialogShower.show(KiwixDialog.StoragePermissionRationale.INSTANCE, new Function0<Unit>() {
            @Override public Unit invoke() {
              requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS);
              return Unit.INSTANCE;
            }
          });

        } else {
          requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS);
        }

        return false;
      }
    }

    return true; // Control reaches here: Either permission granted at install time, or at the time of request
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    switch (requestCode) {
      case PERMISSION_REQUEST_CODE_COARSE_LOCATION: {
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
          Log.e(TAG, "Location permission not granted");

          showToast(this, R.string.permission_refused_location, Toast.LENGTH_LONG);
          closeLocalFileTransferActivity();
          break;
        }
      }

      case PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS: {
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
          Log.e(TAG, "Storage write permission not granted");

          showToast(this, R.string.permission_refused_storage, Toast.LENGTH_LONG);
          closeLocalFileTransferActivity();
          break;
        }
      }
    }
  }

  private boolean isLocationServicesEnabled() {
    LocationManager locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
    boolean gps_enabled = false;
    boolean network_enabled = false;

    try {
      gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    } catch(Exception ex) {ex.printStackTrace();}

    try {
      network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    } catch(Exception ex) {ex.printStackTrace();}

    return (gps_enabled || network_enabled);
  }

  private void requestEnableLocationServices() {
    FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
    Fragment prev = getSupportFragmentManager().findFragmentByTag(RequestEnableLocationServicesDialog.TAG);

    if(prev == null) {
      RequestEnableLocationServicesDialog dialogFragment = new RequestEnableLocationServicesDialog();
      dialogFragment.show(fragmentTransaction, RequestEnableLocationServicesDialog.TAG);
    }
  }

  private void requestEnableWifiP2pServices() {
    FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
    Fragment prev = getSupportFragmentManager().findFragmentByTag(RequestEnableWifiP2pServicesDialog.TAG);

    if(prev == null) {
      RequestEnableWifiP2pServicesDialog dialogFragment = new RequestEnableWifiP2pServicesDialog();
      dialogFragment.show(fragmentTransaction, RequestEnableWifiP2pServicesDialog.TAG);
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    switch (requestCode) {
      case REQUEST_ENABLE_LOCATION_SERVICES: {
        LocationManager locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        if(!(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
          // If neither provider is enabled
          showToast(this, R.string.permission_refused_location, Toast.LENGTH_LONG);
        }
        break;
      }

      case REQUEST_ENABLE_WIFI_P2P: {
        if(!isWifiP2pEnabled()) {
          showToast(this, R.string.request_refused_wifi, Toast.LENGTH_LONG);
        }
        break;
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    receiver = new WifiDirectBroadcastReceiver(manager, channel, this);
    registerReceiver(receiver, intentFilter);
  }

  @Override
  public void onPause() {
    super.onPause();
    unregisterReceiver(receiver);
  }

  @Override
  public void onBackPressed() {
    super.onBackPressed();
    closeLocalFileTransferActivity();
  }
}
