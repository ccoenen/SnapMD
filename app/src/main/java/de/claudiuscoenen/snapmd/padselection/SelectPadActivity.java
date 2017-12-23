package de.claudiuscoenen.snapmd.padselection;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.claudiuscoenen.snapmd.R;
import de.claudiuscoenen.snapmd.SnapMdApplication;
import de.claudiuscoenen.snapmd.api.model.Media;
import de.claudiuscoenen.snapmd.login.LoginActivity;
import de.claudiuscoenen.snapmd.model.Pad;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.Transport;
import okhttp3.OkHttpClient;

public class SelectPadActivity extends AppCompatActivity implements
		PadSelectionFragment.Listener {

	@BindView(R.id.container_pad_selection)
	protected View padSelectionContainer;

	@BindView(R.id.progress)
	protected ProgressBar progressBar;

	private final CompositeDisposable disposables = new CompositeDisposable();
	private SnapMdApplication app;
	private Uri imageUri;
	private String uploadedImageUrl = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_select_pad);
		ButterKnife.bind(this);

		Intent intent = getIntent();
		String action = intent.getAction();
		String type = intent.getType();

		app = (SnapMdApplication) getApplication();

		imageUri = null;
		if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("image/")) {
			imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
		}

		if (savedInstanceState == null) {
			getSupportFragmentManager()
					.beginTransaction()
					.add(R.id.container_image, ImageFragment.newInstance(imageUri), "ImageFragment")
					.commit();

			getSupportFragmentManager()
					.beginTransaction()
					.add(R.id.container_pad_selection, PadSelectionFragment.newInstance(), "PadSelectionFragment")
					.commit();
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		disposables.clear();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.select_pad, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_logout:
				app.getLoginDataRepository().deleteLoginData();
				startActivity(new Intent(this, LoginActivity.class));
				finish();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onPadSelected(Pad pad) {
		socketIoSetup(pad, "test");

		if (imageUri == null) {
			return;
		}

		byte[] bytes;
		try {
			//noinspection ConstantConditions
			bytes = IOUtils.toByteArray(getContentResolver().openInputStream(imageUri));
		} catch (IOException | NullPointerException e) {
			onUploadError(e);
			return;
		}

		// TODO: get progress
		Disposable disposable = app.getApi()
				.uploadImage(bytes)
				.observeOn(AndroidSchedulers.mainThread())
				.doFinally(this::onUploadEnd)
				.subscribe(this::onUploadSuccess, this::onUploadError);

		disposables.add(disposable);
		onUploadStart();
	}

	private Socket socket;
	private JSONObject cursor = null;
	private String documentContent = null;
	private long documentRevision = 0;

	private void socketIOMagicTest(Pad pad, String text) {
		if (socket == null) {
			socketIoSetup(pad, text);
		}
		try {
			Log.v("socketio", "sending cursor focus...");
			JSONObject obj = new JSONObject();
			obj.put("line", 6);
			obj.put("ch", 0);
			obj.put("xRel", 1);
			socket.emit("cursor focus", obj, (Ack) args -> Log.v("socketio", "...cursor focus sent"));
		} catch (JSONException e) {
			Log.e("socketio", e.toString());
		}
	}

	private void socketIoSetup(Pad pad, String text) {
		if (socket != null) {
			socketIOMagicTest(pad, "test123");
			return;
		}

		Log.i("socketio", "Pad selected is " + pad.getText() + " (" + pad.getId() + ")");

		OkHttpClient client = app.getApi().getHttpClient();

		IO.Options opts = new IO.Options();
		opts.secure = true;
		opts.callFactory = client;
		opts.webSocketFactory = client;

		try {
			socket = IO.socket(app.getLoginDataRepository().getServerUrl(), opts);
		} catch (URISyntaxException e) {
			Log.w("socketio", e.toString());
			return;
		}

		socket.io().on(Manager.EVENT_TRANSPORT, args -> {
			Transport transport = (Transport) args[0];

			transport.on(Transport.EVENT_REQUEST_HEADERS, args12 -> {
				@SuppressWarnings("unchecked")
				Map<String, List<String>> headers = (Map<String, List<String>>) args12[0];
				// modify request headers
				String padURL = app.getLoginDataRepository().getServerUrl()+pad.getId();
				Log.v("socketio", "Pad-URL " + padURL);
				headers.put("Referer", Arrays.asList(padURL));
			});

			transport.on(Transport.EVENT_RESPONSE_HEADERS, args12 -> {
				@SuppressWarnings("unchecked")
				Map<String, List<String>> headers = (Map<String, List<String>>) args12[0];
			});

			transport.on(Transport.EVENT_ERROR, args1 -> {
				Exception e = (Exception) args1[0];
				Log.e("socketio", "transport error " + e);
				e.printStackTrace();
				e.getCause().printStackTrace();
			});
		});

		socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
			@Override
			public void call(Object... args) {
				Log.w("socketio", "connected.");
			}
		});

		socket.on(Socket.EVENT_DISCONNECT, args -> Log.w("socketio", "disconnected?"));
		socket.on(Socket.EVENT_CONNECT_TIMEOUT, args -> Log.e("socketio", "connect timeout"));
		socket.on(Socket.EVENT_CONNECT_ERROR, args -> Log.e("socketio", "econnect error"));

		socket.on("doc", args -> {
			try {
				JSONObject ob = (JSONObject) args[0];
				documentContent = ob.getString("str");
				documentRevision = ob.getLong("revision");
				tryAppendImage();
				Log.i("socketio", "doc received, content: " + documentContent);
			} catch (JSONException e) {
				Log.e("json", "json for 'doc' did not contain the exepected format.\n" + e.toString());
			}
		});
		socket.on("online users", args -> {
			Log.v("socketio", "online users received");
			try {
				JSONArray users = ((JSONObject) args[0]).getJSONArray("users");
				for (int i = 0; i < users.length(); i++) {
					JSONObject user = users.getJSONObject(i);
					// String name = user.getString("name");
					Log.v("json", "- parsed user: " + user.getString("name") + " / " + user.getString("id") + " / " + user.getString("userid"));
					if (!user.isNull("cursor")) { // TODO also check for user name or something like that!
						cursor = user.getJSONObject("cursor");
					}
				}
				tryAppendImage();
			} catch (JSONException e) {
				Log.e("json", "json for 'online users' did not contain the expected format\n" + e.toString());
			}
		});
		//socket.on("refresh", args -> Log.v("socketio", "refresh received"));
		//socket.on("cursor activity", args -> Log.v("socketio", "cursor activity received"));
		//socket.on("cursor blur", args -> Log.v("socketio", "cursor blur received"));
		//socket.on("cursor focus", args -> Log.v("socketio", "cursor focus received"));
		//socket.on("selection", args -> Log.v("socketio", "selection received"));

		socket.connect();
		Log.i("socketio", "trying to connect");
	}

	private void tryAppendImage() {
		if (documentContent == null) {
			Log.i("bla", "Document not yet loaded");
			return;
		}

		// todo check availability of a valid cursor
		if (false && cursor == null) {
			Log.i("bla", "No Cursor Found");
			return;
		}
		if (uploadedImageUrl == null) {
			Log.i("bla", "no Image URL");
			return;
		}

		Log.i("bla", "would now edit the document");
		//cursor.getLong("line");
		//cursor.getLong("ch");
		//cursor.getLong("xRel");
		// socket.emit("operation", "bla");

		// 42["operation", 1, [163, "https", 1], {ranges: [{anchor: 168, head: 168}]}]
		//        ^name    ^rev ^before ^ins ^after
		JSONArray operation = new JSONArray();
		operation.put(documentContent.length() - 1);
		// todo could also include some meta info in the alt attribute.
		operation.put("\n![](" + uploadedImageUrl + ")\n");
		operation.put(1);

		JSONObject selection = new JSONObject();
		JSONArray selectionArray = new JSONArray();
		JSONObject selectionItem = new JSONObject();
		try {
			selection.put("ranges", selectionArray);
			selectionArray.put(selectionItem);
			selectionItem.put("anchor", documentContent.length());
			selectionItem.put("head", documentContent.length());
			socket.emit("operation", documentRevision, operation, selection);
			documentContent = null;
			uploadedImageUrl = null;
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	private void onUploadSuccess(Media media) {
		uploadedImageUrl = media.getLink();
		tryAppendImage();
		Toast.makeText(this, media.getLink(), Toast.LENGTH_LONG).show();
	}

	private void onUploadError(Throwable t) {
		Toast.makeText(this, t.getMessage(), Toast.LENGTH_LONG).show();
	}

	private void onUploadStart() {
		padSelectionContainer.setEnabled(false);
		progressBar.setVisibility(View.VISIBLE);
	}

	private void onUploadEnd() {
		padSelectionContainer.setEnabled(true);
		progressBar.setVisibility(View.GONE);
	}
}
