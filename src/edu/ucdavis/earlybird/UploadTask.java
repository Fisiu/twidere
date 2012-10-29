package edu.ucdavis.earlybird;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.mariotaku.twidere.util.Utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.provider.Settings.Secure;

public class UploadTask extends AsyncTask<Void, Void, Void> {

	private static final String LAST_UPLOAD_DATE = "last_upload_time";
	private static final double MILLSECS_HALF_DAY = 1000 * 60 * 60 * 12;

	private final String device_id;
	private final Context context;

	private static final String PROFILE_SERVER_URL = "http://weik.metaisle.com/profiles";

	// private static final String PROFILE_SERVER_URL =
	// "http://192.168.0.105:3000/profiles";

	public UploadTask(final Context context) {
		this.context = context;
		device_id = Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
	}

	public void uploadMultipart(final String urlStr, final File file) {
		final String app_root = file.getParent();
		final File tmp_dir = new File(app_root + "/tmp");
		if (!tmp_dir.exists()) {
			if (!tmp_dir.mkdirs()) {
				ProfilingUtil.log("cannot create tmp, do nothing.");
				return;
			}
		}
		final File tmp = new File(tmp_dir, file.getName());
		file.renameTo(tmp);

		final String lineEnd = "\r\n";
		final String twoHyphens = "--";

		// generating byte[] boundary here

		final String boundary = "---------------------------131976563820899716501910991218";
		HttpURLConnection conn = null;
		DataOutputStream outputStream = null;

		int bytesRead, bytesAvailable, bufferSize;
		byte[] buffer;
		final int maxBufferSize = 1 * 1024 * 1024;

		try {
			final URL uploadUrl = new URL(urlStr);
			final FileInputStream fileInputStream = new FileInputStream(tmp);
			conn = Utils.getConnection(context, uploadUrl);
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);

			String multipartFormStart = new String();

			multipartFormStart += twoHyphens + boundary + lineEnd
					+ "Content-Disposition: form-data; name=\"upload\"; filename=\"" + tmp.getName() + "\"" + lineEnd;
			multipartFormStart += "Content-Type: text/csv" + lineEnd + "Content-Length: " + tmp.length() + lineEnd
					+ lineEnd;

			final String multipartFormEnd = lineEnd + twoHyphens + boundary + twoHyphens + lineEnd;

			final int totalLength = multipartFormStart.length() + (int) tmp.length() + multipartFormEnd.length();
			ProfilingUtil.log("totallenhth " + totalLength);
			conn.setFixedLengthStreamingMode(totalLength);

			outputStream = new DataOutputStream(conn.getOutputStream());
			outputStream.writeBytes(multipartFormStart);

			// Upload file
			bytesAvailable = fileInputStream.available();
			bufferSize = Math.min(bytesAvailable, maxBufferSize);
			ProfilingUtil.log("bufferSize " + bufferSize);
			buffer = new byte[bufferSize];
			bytesRead = fileInputStream.read(buffer, 0, bufferSize);
			ProfilingUtil.log("bytesRead " + bytesRead);

			while (bytesRead > 0) {
				outputStream.write(buffer, 0, bufferSize);
				bytesAvailable = fileInputStream.available();
				bufferSize = Math.min(bytesAvailable, maxBufferSize);
				bytesRead = fileInputStream.read(buffer, 0, bufferSize);
			}

			outputStream.writeBytes(multipartFormEnd);

			ProfilingUtil.log("connection outputstream size is " + outputStream.size());

			// finished with POST request body

			// Responses from the server (code and message)
			final int serverResponseCode = conn.getResponseCode();
			final String serverResponseMessage = conn.getResponseMessage();

			ProfilingUtil.log("server response code " + serverResponseCode);
			ProfilingUtil.log("server response message " + serverResponseMessage);

			if (serverResponseCode / 100 == 2) {
				tmp.delete();
			} else {
				putBackProfile(tmp, file);
			}

			fileInputStream.close();

			outputStream.flush();
			outputStream.close();

		} catch (final MalformedURLException e) {
			e.printStackTrace();
			putBackProfile(tmp, file);
		} catch (final IOException e) {
			e.printStackTrace();
			putBackProfile(tmp, file);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}

	}

	@Override
	protected Void doInBackground(final Void... params) {

		final SharedPreferences prefs = context.getSharedPreferences("ucd_data_profiling", Context.MODE_PRIVATE);

		if (prefs.contains(LAST_UPLOAD_DATE)) {
			final long lastUpload = prefs.getLong(LAST_UPLOAD_DATE, System.currentTimeMillis());
			final double deltaDays = (System.currentTimeMillis() - lastUpload) / (MILLSECS_HALF_DAY * 2);
			if (deltaDays < 1) {
				ProfilingUtil.log("Uploaded less than 1 day ago.");
				return null;
			}
		}

		final File root = context.getFilesDir();
		final File[] files = root.listFiles(new CSVFileFilter());

		uploadToNode(files);
		prefs.edit().putLong(LAST_UPLOAD_DATE, System.currentTimeMillis()).commit();
		return null;
	}

	private boolean uploadToNode(final File... files) {
		for (final File file : files) {
			if (file.isDirectory()) {
				continue;
			}
			final String url = PROFILE_SERVER_URL + "/" + device_id + "/"
					+ file.getName().replaceFirst("[.][^.]+$", "");
			ProfilingUtil.log(url);
			uploadMultipart(url, file);
		}
		return false;
	}

	public static void putBackProfile(final File tmp, final File profile) {
		boolean success;
		if (profile.exists()) {
			try {
				final BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmp, true));
				final BufferedInputStream bis = new BufferedInputStream(new FileInputStream(profile));

				final byte[] buffer = new byte[1024];
				int len = 0;
				while ((len = bis.read(buffer)) != -1) {
					bos.write(buffer, 0, len);
				}

				bis.close();
				bos.flush();
				bos.close();

			} catch (final FileNotFoundException e) {
				e.printStackTrace();
				success = false;
			} catch (final IOException e) {
				e.printStackTrace();
				success = false;
			}

			success = true;

			if (success && tmp.renameTo(profile) && tmp.delete()) {
				ProfilingUtil.log("put profile back success");
			} else {
				ProfilingUtil.log("put profile back failed");
			}
		} else {
			if (tmp.renameTo(profile)) {
				ProfilingUtil.log("put profile back success");
			} else {
				ProfilingUtil.log("put profile back failed");
			}
		}
	}
}