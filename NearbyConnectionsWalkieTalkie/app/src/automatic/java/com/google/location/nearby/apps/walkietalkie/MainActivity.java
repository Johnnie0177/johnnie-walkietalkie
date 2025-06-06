package com.google.location.nearby.apps.walkietalkie;

import static com.google.android.gms.common.util.IOUtils.copyStream;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import android.provider.MediaStore;
import android.text.SpannableString;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
//import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

/**
 * Our WalkieTalkie Activity. This Activity has 3 {@link State}s.
 *
 * <p>{@link State#UNKNOWN}: We cannot do anything while we're in this state. The app is likely in
 * the background.
 *
 * <p>{@link State#SEARCHING}: Our default state (after we've connected). We constantly listen for a
 * device to advertise near us, while simultaneously advertising ourselves.
 *
 * <p>{@link State#CONNECTED}: We've connected to another device and can now talk to them by holding
 * down the volume keys and speaking into the phone. Advertising and discovery have both stopped.
 */
public class MainActivity extends ConnectionsActivity {
  /** If true, debug logs are shown on the device. */
  private static final boolean DEBUG = true;

  /**
   * The connection strategy we'll use for Nearby Connections. In this case, we've decided on
   * P2P_STAR, which is a combination of Bluetooth Classic and WiFi Hotspots.
   */
  private static final Strategy STRATEGY = Strategy.P2P_STAR;

  /** Length of state change animations. */
  private static final long ANIMATION_DURATION = 600;

  /**
   * A set of background colors. We'll hash the authentication token we get from connecting to a
   * device to pick a color randomly from this list. Devices with the same background color are
   * talking to each other securely (with 1/COLORS.length chance of collision with another pair of
   * devices).
   */
  @ColorInt
  private static final int[] COLORS =
      new int[] {
        0xFFF44336 /* red */,
        0xFF9C27B0 /* deep purple */,
        0xFF00BCD4 /* teal */,
        0xFF4CAF50 /* green */,
        0xFFFFAB00 /* amber */,
        0xFFFF9800 /* orange */,
        0xFF795548 /* brown */
      };

  /**
   * This service id lets us find other nearby devices that are interested in the same thing. Our
   * sample does exactly one thing, so we hardcode the ID.
   */
  private static final String SERVICE_ID =
      "com.google.location.nearby.apps.walkietalkie.automatic.SERVICE_ID";

  /**
   * The state of the app. As the app changes states, the UI will update and advertising/discovery
   * will start/stop.
   */
  private State mState = State.UNKNOWN;

  /** A random UID used as this device's endpoint name. */
  private String mName;

  /**
   * The background color of the 'CONNECTED' state. This is randomly chosen from the {@link #COLORS}
   * list, based off the authentication token.
   */
  @ColorInt private int mConnectedColor = COLORS[0];

  /** Displays the previous state during animation transitions. */
  private TextView mPreviousStateView;

  /** Displays the current state. */
  private TextView mCurrentStateView;

  /** An animator that controls the animation from previous state to current state. */
  @Nullable private Animator mCurrentAnimator;

  /** A running log of debug messages. Only visible when DEBUG=true. */
  private TextView mDebugLogView;

  /** Buttons and dropdowns to send pictures and test features. */
  private Button mSendPictureButton;
  private Button mTest01Button;
  private Button mTest02Button;
  private Spinner mSpinner01;
  private Spinner mSpinner02;
  private Spinner mSpinner03;

  /** Listens to holding/releasing the volume rocker. */
  private final GestureDetector mGestureDetector =
      new GestureDetector(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP) {
        @Override
        protected void onHold() {
          logV("onHold");
          startRecording();
        }

        @Override
        protected void onRelease() {
          logV("onRelease");
          stopRecording();
        }
      };

  /** For recording audio as the user speaks. */
  @Nullable private AudioRecorder mRecorder;

  /** For playing audio from other users nearby. */
  @Nullable private AudioPlayer mAudioPlayer;

  /** The phone's original media volume. */
  private int mOriginalVolume;

  /** Media picker for picture selection. */
  private ActivityResultLauncher<PickVisualMediaRequest> mMediaPicker;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    getSupportActionBar()
        .setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.actionBar));

    mPreviousStateView = (TextView) findViewById(R.id.previous_state);
    mCurrentStateView = (TextView) findViewById(R.id.current_state);
    mDebugLogView = (TextView) findViewById(R.id.debug_log);
    mDebugLogView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
    mDebugLogView.setMovementMethod(new ScrollingMovementMethod());
    mName = generateRandomName();
    ((TextView) findViewById(R.id.name)).setText(mName);

    // Media picker and send-picture button, per assignment.
    mMediaPicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), new ImagePickerCallback<>());
    mSendPictureButton = (Button) findViewById(R.id.sendPictureButton);
    mSendPictureButton.setOnClickListener(new SendPictureListener());

    // Experiments, requesting permissions and opening picture files.
    // TODO: remove cruft post-experimentation
    mTest01Button = (Button) findViewById(R.id.test01Button);
    mTest01Button.setOnClickListener(new Test01Listener());

    mTest02Button = (Button) findViewById(R.id.test02Button);
    mTest02Button.setOnClickListener(new Test02Listener());

    ArrayAdapter<String> adapter = null;
    int spinnerLayout = android.R.layout.simple_spinner_dropdown_item;
    String [] spinnerItems = null;

    mSpinner01 = (Spinner) findViewById(R.id.spinner01);
    Spinner01Listener spinner01Listener = new Spinner01Listener();
    spinnerItems = spinner01Listener.getSelections();
    adapter = new ArrayAdapter<>(this, spinnerLayout, spinnerItems);
    mSpinner01.setAdapter(adapter);
    mSpinner01.setOnItemSelectedListener(spinner01Listener);

    mSpinner02 = (Spinner) findViewById(R.id.spinner02);
    Spinner02Listener spinner02Listener = new Spinner02Listener();
    spinnerItems = spinner02Listener.getSelections();
    adapter = new ArrayAdapter<>(this, spinnerLayout, spinnerItems);
    mSpinner02.setAdapter(adapter);
    mSpinner02.setOnItemSelectedListener(spinner02Listener);

    mSpinner03 = (Spinner) findViewById(R.id.spinner03);
    Spinner03Listener spinner03Listener = new Spinner03Listener();
    spinnerItems = spinner03Listener.getSelections();
    adapter = new ArrayAdapter<>(this, spinnerLayout, spinnerItems);
    mSpinner03.setAdapter(adapter);
    mSpinner03.setOnItemSelectedListener(spinner03Listener);

  }

  private String [] mOpenChoices = { "show file", "choose app", };
  private int mOpenMode = 0;
  private String [] mDataChoices = { "set path", "ignore path", };
  private int mDataMode = 0;
  private String [] mActionChoices = { "pick", "view", "review", "get content", "main", };
  private int mActionMode = 0;

  class Test01Listener implements View.OnClickListener {
    public void onClick(View v) {
      //listApplications();
      //listPackages();
      openFile("sdcard/Pictures/TheCat.png");
    }
  }

  protected void openFile(String fileName) {
    logD("open file: " + fileName);
    logD("ActionMode: " + mActionChoices[mActionMode]);
    logD("DataMode: " + mDataChoices[mDataMode]);
    logD("OpenMode: " + mOpenChoices[mOpenMode]);

    Intent intent = null;
    switch (mActionMode) {
      case 0: intent = new Intent(Intent.ACTION_PICK); break;
      case 1: intent = new Intent(Intent.ACTION_VIEW); break;
      case 2: intent = new Intent(MediaStore.ACTION_REVIEW); break;
      case 3: intent = new Intent(Intent.ACTION_GET_CONTENT); break;
      case 4: intent = new Intent(Intent.ACTION_MAIN); break;
      default:
        logW("unrecognized action mode: " + mActionMode);
        return;
    }
    logD("intent: " + intent);
    intent.setType("image/*");
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    //String packageName = "com.google.android.apps.nbu.files";
    //intent.setData(Uri.parse("market://details?id=" + packageName));

    switch (mDataMode) {
      case 0:
        intent.setData(Uri.parse(fileName));
        break;
      case 1:
        // ignore file name
        break;
      default:
        logW("unrecognized data mode: " + mDataMode);
        return;
    }

    try {
      switch (mOpenMode) {
        case 0:
          startActivity(intent);
          break;
        case 1:
          startActivity(Intent.createChooser(intent, "Choose"));
          break;
        default:
          logW("unrecognized open mode: " + mOpenMode);
          return;
      }
    }
    catch(Exception e) {
      logW("error: " + e.getMessage());
    }
  }

  protected void listApplications() {
    logD("listApplications()");
    PackageManager pm = getPackageManager();
    List<ApplicationInfo> appList = pm.getInstalledApplications(PackageManager.GET_META_DATA);
    int index = 0;
    for (ApplicationInfo info : appList) {
      String name = info.name;
      if (name != null) {
        logD("" + index + ": " + info.name);
      }
      index++;
    }
  }

  protected void listPackages() {
    logD("listPackages()");
    PackageManager pm = getPackageManager();
    List<PackageInfo> packageList = pm.getInstalledPackages(PackageManager.GET_META_DATA);
    int index = 0;
    for (PackageInfo info : packageList) {
      String name = info.packageName;
      logD("" + index + ": " + name);
      index++;
    }
  }

  class Test02Listener implements View.OnClickListener {
    public void onClick(View v) {
      redoPermissionsRequest();
    }
  }

  protected void redoPermissionsRequest() {
    String [] requiredPermissions = {
      Manifest.permission.BLUETOOTH_SCAN,
      Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.BLUETOOTH_CONNECT,
      Manifest.permission.ACCESS_WIFI_STATE,
      Manifest.permission.CHANGE_WIFI_STATE,
      Manifest.permission.NEARBY_WIFI_DEVICES,
      Manifest.permission.READ_EXTERNAL_STORAGE,
      Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };
    if (Build.VERSION.SDK_INT < 23) {
      logD("onClick() calling ActivityCompat.requestPermissions()");
      logD("numPermissions: " + requiredPermissions.length);
      for(String p : requiredPermissions) {
        logD(p.substring(19));
      }
      ActivityCompat.requestPermissions(MainActivity.this, requiredPermissions, 2);
    } else {
      logD("re-requesting permissions: " + requiredPermissions.length);
      //for(String p : requiredPermissions) {
      //  logD(p.substring(19));
      //}
      requestPermissions(requiredPermissions, 2);
    }
  }

  private void openImageToView() {
    logD("open image type with market uri");
    Intent intent = new Intent(Intent.ACTION_VIEW);
    //Intent intent = new Intent(Intent.ACTION_MAIN);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.setType("image/*");
    //intent.setData(Uri.parse("sdcard/Pictures/TheCat.png"));
    //String packageName = "com.google.android.apps.nbu.files";
    //intent.setData(Uri.parse("market://details?id=" + packageName));
    try {
      //startActivity(intent);
      startActivity(Intent.createChooser(intent, "Choose"));
    }
    catch(Exception e) {
      logW("error: " + e.getMessage());
    }
  }

  class Spinner01Listener implements AdapterView.OnItemSelectedListener {
    public String [] getSelections() {
      return mOpenChoices;
    }

    public void onItemSelected(AdapterView av, View v, int pos, long id) {
      logD("spinner 01 selection: " + id);
      mOpenMode = (int)id;
      logD("Open mode: " + mOpenChoices[mOpenMode]);
    }

    public void onNothingSelected(AdapterView av) {
      logD("nothing selected");
    }
  }

  class Spinner02Listener implements AdapterView.OnItemSelectedListener {
    public String [] getSelections() {
      return mDataChoices;
    }

    public void onItemSelected(AdapterView av, View v, int pos, long id) {
      logD("spinner 02 selection: " + id);
      mDataMode = (int)id;
      logD("Data mode: " + mDataChoices[mDataMode]);
    }

    public void onNothingSelected(AdapterView av) {
      logD("nothing selected");
    }
  }

  class Spinner03Listener implements AdapterView.OnItemSelectedListener {
    public String [] getSelections() {
      return mActionChoices;
    }

    public void onItemSelected(AdapterView av, View v, int pos, long id) {
      logD("spinner 02 selection: " + id);
      mActionMode = (int)id;
      logD("Action mode: " + mActionChoices[mActionMode]);
    }

    public void onNothingSelected(AdapterView av) {
      logD("nothing selected");
    }
  }

  class ImagePickerCallback<T> implements ActivityResultCallback<T> {
    public void onActivityResult(T uri) {

      // User has selected picture, construct a file payload for it, and send it
      // to the connected device.

      if (uri == null) {
        logW("null photo uri");
        return;
      }
      Uri uriObj = (Uri)uri;
      logD("uriObj: " + uriObj);

      ParcelFileDescriptor pfd;
      try {
        pfd = getContentResolver().openFileDescriptor(uriObj, "r");
      } catch(Exception e) {
        logE("couldn't make pfd", e);
        return;
      }
      Payload filePayload;
      try {
        filePayload = Payload.fromFile(pfd);
      } catch(Exception e) {
        logE("couldn't make payload from pfd", e);
        return;
      }
      String payloadTypeStr = "unassigned";
      switch (filePayload.getType()) {
        case Payload.Type.BYTES: payloadTypeStr = "bytes"; break;
        case Payload.Type.FILE: payloadTypeStr = "file"; break;
        case Payload.Type.STREAM: payloadTypeStr = "stream"; break;
        default: payloadTypeStr = "unknown";
      }
      logD("sending payload: " + filePayload);
      logD("payload type: " + filePayload.getType() + ": " + payloadTypeStr);
      send(filePayload);
    }
  }

  class SendPictureListener implements View.OnClickListener {
    public void onClick(View v) {

      // Get picture selection from user.

      if (!ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable()) {
        logW("photo picker not available");
        return;
      }

      try {
        ActivityResultContracts.PickVisualMedia.VisualMediaType mediaType =
          (ActivityResultContracts.PickVisualMedia.VisualMediaType) ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE;
        PickVisualMediaRequest.Builder requestBuilder = new PickVisualMediaRequest.Builder();
        requestBuilder.setMediaType(mediaType);
        PickVisualMediaRequest mediaRequest = requestBuilder.build();
        mMediaPicker.launch(mediaRequest);
      } catch(Exception e) {
        logW("error: " + e.getMessage());
      }
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (mState == State.CONNECTED && mGestureDetector.onKeyEvent(event)) {
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onStart() {
    super.onStart();

    // Set the media volume to max.
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    mOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);

    setState(State.SEARCHING);
  }

  @Override
  protected void onStop() {
    // Restore the original volume.
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
    setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

    // Stop all audio-related threads
    if (isRecording()) {
      stopRecording();
    }
    if (isPlaying()) {
      stopPlaying();
    }

    // After our Activity stops, we disconnect from Nearby Connections.
    setState(State.UNKNOWN);

    if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
      mCurrentAnimator.cancel();
    }

    super.onStop();
  }

  @Override
  public void onBackPressed() {
    if (getState() == State.CONNECTED) {
      setState(State.SEARCHING);
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onEndpointDiscovered(Endpoint endpoint) {
    // We found an advertiser!
    stopDiscovering();
    connectToEndpoint(endpoint);
  }

  @Override
  protected void onConnectionInitiated(Endpoint endpoint, ConnectionInfo connectionInfo) {
    // A connection to another device has been initiated! We'll use the auth token, which is the
    // same on both devices, to pick a color to use when we're connected. This way, users can
    // visually see which device they connected with.
    mConnectedColor = COLORS[connectionInfo.getAuthenticationToken().hashCode() % COLORS.length];

    // We accept the connection immediately.
    acceptConnection(endpoint);
  }

  @Override
  protected void onEndpointConnected(Endpoint endpoint) {
    Toast.makeText(
            this, getString(R.string.toast_connected, endpoint.getName()), Toast.LENGTH_SHORT)
        .show();
    setState(State.CONNECTED);
  }

  @Override
  protected void onEndpointDisconnected(Endpoint endpoint) {
    Toast.makeText(
            this, getString(R.string.toast_disconnected, endpoint.getName()), Toast.LENGTH_SHORT)
        .show();
    setState(State.SEARCHING);
  }

  @Override
  protected void onConnectionFailed(Endpoint endpoint) {
    // Let's try someone else.
    if (getState() == State.SEARCHING) {
      startDiscovering();
    }
  }

  /**
   * The state has changed. I wonder what we'll be doing now.
   *
   * @param state The new state.
   */
  private void setState(State state) {
    if (mState == state) {
      logW("State set to " + state + " but already in that state");
      return;
    }

    logD("State set to " + state);
    State oldState = mState;
    mState = state;
    onStateChanged(oldState, state);
  }

  /** @return The current state. */
  private State getState() {
    return mState;
  }

  /**
   * State has changed.
   *
   * @param oldState The previous state we were in. Clean up anything related to this state.
   * @param newState The new state we're now in. Prepare the UI for this state.
   */
  private void onStateChanged(State oldState, State newState) {
    if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
      mCurrentAnimator.cancel();
    }

    // Update Nearby Connections to the new state.
    switch (newState) {
      case SEARCHING:
        disconnectFromAllEndpoints();
        startDiscovering();
        startAdvertising();
        break;
      case CONNECTED:
        stopDiscovering();
        stopAdvertising();
        break;
      case UNKNOWN:
        stopAllEndpoints();
        break;
      default:
        // no-op
        break;
    }

    // Update the UI.
    switch (oldState) {
      case UNKNOWN:
        // Unknown is our initial state. Whatever state we move to,
        // we're transitioning forwards.
        transitionForward(oldState, newState);
        break;
      case SEARCHING:
        switch (newState) {
          case UNKNOWN:
            transitionBackward(oldState, newState);
            break;
          case CONNECTED:
            transitionForward(oldState, newState);
            break;
          default:
            // no-op
            break;
        }
        break;
      case CONNECTED:
        // Connected is our final state. Whatever new state we move to,
        // we're transitioning backwards.
        transitionBackward(oldState, newState);
        break;
    }
  }

  /** Transitions from the old state to the new state with an animation implying moving forward. */
  @UiThread
  private void transitionForward(State oldState, final State newState) {
    mPreviousStateView.setVisibility(View.VISIBLE);
    mCurrentStateView.setVisibility(View.VISIBLE);

    updateTextView(mPreviousStateView, oldState);
    updateTextView(mCurrentStateView, newState);

    if (ViewCompat.isLaidOut(mCurrentStateView)) {
      mCurrentAnimator = createAnimator(false /* reverse */);
      mCurrentAnimator.addListener(
          new AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animator) {
              updateTextView(mCurrentStateView, newState);
            }
          });
      mCurrentAnimator.start();
    }
  }

  /** Transitions from the old state to the new state with an animation implying moving backward. */
  @UiThread
  private void transitionBackward(State oldState, final State newState) {
    mPreviousStateView.setVisibility(View.VISIBLE);
    mCurrentStateView.setVisibility(View.VISIBLE);

    updateTextView(mCurrentStateView, oldState);
    updateTextView(mPreviousStateView, newState);

    if (ViewCompat.isLaidOut(mCurrentStateView)) {
      mCurrentAnimator = createAnimator(true /* reverse */);
      mCurrentAnimator.addListener(
          new AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animator) {
              updateTextView(mCurrentStateView, newState);
            }
          });
      mCurrentAnimator.start();
    }
  }

  @NonNull
  private Animator createAnimator(boolean reverse) {
    Animator animator;
    if (Build.VERSION.SDK_INT >= 21) {
      int cx = mCurrentStateView.getMeasuredWidth() / 2;
      int cy = mCurrentStateView.getMeasuredHeight() / 2;
      int initialRadius = 0;
      int finalRadius = Math.max(mCurrentStateView.getWidth(), mCurrentStateView.getHeight());
      if (reverse) {
        int temp = initialRadius;
        initialRadius = finalRadius;
        finalRadius = temp;
      }
      animator =
          ViewAnimationUtils.createCircularReveal(
              mCurrentStateView, cx, cy, initialRadius, finalRadius);
    } else {
      float initialAlpha = 0f;
      float finalAlpha = 1f;
      if (reverse) {
        float temp = initialAlpha;
        initialAlpha = finalAlpha;
        finalAlpha = temp;
      }
      mCurrentStateView.setAlpha(initialAlpha);
      animator = ObjectAnimator.ofFloat(mCurrentStateView, "alpha", finalAlpha);
    }
    animator.addListener(
        new AnimatorListener() {
          @Override
          public void onAnimationCancel(Animator animator) {
            mPreviousStateView.setVisibility(View.GONE);
            mCurrentStateView.setAlpha(1);
          }

          @Override
          public void onAnimationEnd(Animator animator) {
            mPreviousStateView.setVisibility(View.GONE);
            mCurrentStateView.setAlpha(1);
          }
        });
    animator.setDuration(ANIMATION_DURATION);
    return animator;
  }

  /** Updates the {@link TextView} with the correct color/text for the given {@link State}. */
  @UiThread
  private void updateTextView(TextView textView, State state) {
    switch (state) {
      case SEARCHING:
        textView.setBackgroundResource(R.color.state_searching);
        textView.setText(R.string.status_searching);
        break;
      case CONNECTED:
        textView.setBackgroundColor(mConnectedColor);
        textView.setText(R.string.status_connected);
        break;
      default:
        textView.setBackgroundResource(R.color.state_unknown);
        textView.setText(R.string.status_unknown);
        break;
    }
  }

  /** {@see ConnectionsActivity#onReceive(Endpoint, Payload)} */
  @Override
  protected void onReceive(Endpoint endpoint, Payload payload) {
    logD("receive endpoint: " + endpoint);
    logD("receive payload: " + payload);
    String payloadTypeStr = "unassigned";
    switch (payload.getType()) {
      case Payload.Type.BYTES: payloadTypeStr = "bytes"; break;
      case Payload.Type.FILE: payloadTypeStr = "file"; break;
      case Payload.Type.STREAM: payloadTypeStr = "stream"; break;
      default: payloadTypeStr = "unknown";
    }
    logD("receive type " + payload.getType() + ": " + payloadTypeStr);

    if (payload.getType() == Payload.Type.STREAM) {
      if (mAudioPlayer != null) {
        mAudioPlayer.stop();
        mAudioPlayer = null;
      }

      AudioPlayer player = new AudioPlayer(payload.asStream().asInputStream()) {
        @WorkerThread
        @Override
        protected void onFinish() {
          runOnUiThread(new Runnable() {
            @UiThread
            @Override
            public void run() {
              mAudioPlayer = null;
            }
          });
        }
      };
      logD("receiving stream: starting audio player");
      mAudioPlayer = player;
      player.start();
    }
    else if (payload.getType() == Payload.Type.FILE) {
      Uri payloadUri = payload.asFile().asUri();
      logD("file payload started: " + payloadUri);
      mCurrentEndpoint = endpoint;
      mCurrentPayload = payload;
    }
    else {
      logD("ignoring payload of type: " + payload.getType());
    }
  }

  private Endpoint mCurrentEndpoint = null;
  private Payload mCurrentPayload = null;

  /** {@see ConnectionsActivity#onTransferComplete(Endpoint)} */
  @Override
  protected void onTransferComplete(Endpoint endpoint) {
    Payload payload = mCurrentPayload;
    boolean mismatch = (endpoint != mCurrentEndpoint || payload == null);
    mCurrentEndpoint = null;
    mCurrentPayload = null;
    if (mismatch) {
      logW("payload transfer mismatch");
      return;
    }
    Uri payloadUri = payload.asFile().asUri();
    logD("file payload complete: " + payloadUri);

    String fileName = "Wt_" + System.currentTimeMillis() + ".jpg";
    logD("local file name: " + fileName);

    // Write payload to a local file.
    File fileObj = null;
    try {
      //File cacheFile = new File(this.getCacheDir(), fileName);
      fileObj = new File("sdcard/Pictures", fileName);
      logD("local file path: " + fileObj);
      InputStream in = this.getContentResolver().openInputStream(payloadUri);
      logD("starting stream copy");
      copyStream(in, new FileOutputStream(fileObj));
      logD("done stream copy");
    } catch (Exception e) {
      logW("error: " + e.getMessage());
    } finally {
      this.getContentResolver().delete(payloadUri, null, null);
    }

    if (fileObj == null || !fileObj.exists()){
      logW("file creation failed: " + fileObj);
      return;
    }
    logD("file written: " + fileObj);
    logD("file size: " + fileObj.length());
    openFile(fileObj.toString());

    /*
    Uri fileUri = Uri.parse(fileObj.toString());
    logD("viewing fileUri: " + fileUri);
    Intent intent = new Intent(Intent.ACTION_PICK);
    intent.setDataAndType(fileUri, "image/*");
    try {
      //startActivityForResult(Intent.createChooser(intent, "Select Picture"), 1234);
      startActivity( Intent.createChooser(intent, "Choose Application"));
    }
    catch(Exception e) {
      logW("error: " + e.getMessage());
    }
    */
  }

  /** Stops all currently streaming audio tracks. */
  private void stopPlaying() {
    logV("stopPlaying()");
    if (mAudioPlayer != null) {
      mAudioPlayer.stop();
      mAudioPlayer = null;
    }
  }

  /** @return True if currently playing. */
  private boolean isPlaying() {
    return mAudioPlayer != null;
  }

  /** Starts recording sound from the microphone and streaming it to all connected devices. */
  private void startRecording() {
    logV("startRecording()");
    try {
      ParcelFileDescriptor[] payloadPipe = ParcelFileDescriptor.createPipe();

      // Send the first half of the payload (the read side) to Nearby Connections.
      send(Payload.fromStream(payloadPipe[0]));

      // Use the second half of the payload (the write side) in AudioRecorder.
      mRecorder = new AudioRecorder(payloadPipe[1]);
      mRecorder.start();
    } catch (IOException e) {
      logE("startRecording() failed", e);
    }
  }

  /** Stops streaming sound from the microphone. */
  private void stopRecording() {
    logV("stopRecording()");
    if (mRecorder != null) {
      mRecorder.stop();
      mRecorder = null;
    }
  }

  /** @return True if currently streaming from the microphone. */
  private boolean isRecording() {
    return mRecorder != null && mRecorder.isRecording();
  }

  /** {@see ConnectionsActivity#getRequiredPermissions()} */
  @Override
  protected String[] getRequiredPermissions() {
    return join(
        super.getRequiredPermissions(),
        Manifest.permission.RECORD_AUDIO);
  }

  /** Joins 2 arrays together. */
  private static String[] join(String[] a, String... b) {
    String[] join = new String[a.length + b.length];
    System.arraycopy(a, 0, join, 0, a.length);
    System.arraycopy(b, 0, join, a.length, b.length);
    return join;
  }

  /**
   * Queries the phone's contacts for their own profile, and returns their name. Used when
   * connecting to another device.
   */
  @Override
  protected String getName() {
    return mName;
  }

  /** {@see ConnectionsActivity#getServiceId()} */
  @Override
  public String getServiceId() {
    return SERVICE_ID;
  }

  /** {@see ConnectionsActivity#getStrategy()} */
  @Override
  public Strategy getStrategy() {
    return STRATEGY;
  }

  @Override
  protected void logV(String msg) {
    super.logV(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_verbose)));
  }

  @Override
  protected void logD(String msg) {
    super.logD(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_debug)));
  }

  @Override
  protected void logW(String msg) {
    super.logW(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
  }

  @Override
  protected void logW(String msg, Throwable e) {
    super.logW(msg, e);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
  }

  @Override
  protected void logE(String msg, Throwable e) {
    super.logE(msg, e);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_error)));
  }

  private void appendToLogs(CharSequence msg) {
    mDebugLogView.append("\n");
    mDebugLogView.append(DateFormat.format("hh:mm", System.currentTimeMillis()) + ": ");
    mDebugLogView.append(msg);
  }

  private static CharSequence toColor(String msg, int color) {
    SpannableString spannable = new SpannableString(msg);
    spannable.setSpan(new ForegroundColorSpan(color), 0, msg.length(), 0);
    return spannable;
  }

  private static String generateRandomName() {
    String name = "";
    Random random = new Random();
    for (int i = 0; i < 5; i++) {
      name += random.nextInt(10);
    }
    return name;
  }

  /**
   * Provides an implementation of Animator.AnimatorListener so that we only have to override the
   * method(s) we're interested in.
   */
  private abstract static class AnimatorListener implements Animator.AnimatorListener {
    @Override
    public void onAnimationStart(Animator animator) {}

    @Override
    public void onAnimationEnd(Animator animator) {}

    @Override
    public void onAnimationCancel(Animator animator) {}

    @Override
    public void onAnimationRepeat(Animator animator) {}
  }

  /** States that the UI goes through. */
  public enum State {
    UNKNOWN,
    SEARCHING,
    CONNECTED
  }
}
