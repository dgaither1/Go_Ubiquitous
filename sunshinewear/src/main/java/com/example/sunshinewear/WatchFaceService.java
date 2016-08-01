package com.example.sunshinewear;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class WatchFaceService extends CanvasWatchFaceService {

    private final String OPEN_WEATHER_MAP_API_KEY = "";
    public @interface LocationStatus {}

    public static final int LOCATION_STATUS_SERVER_DOWN = 1;
    public static final int LOCATION_STATUS_SERVER_INVALID = 2;

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MUTE_ALPHA = 100;
        static final int NORMAL_ALPHA = 255;
        static final int MSG_UPDATE_TIME = 0;
        static final int MSG_UPDATE_WEATHER = 1;
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        final String DEGREE  = "\u00b0";
        boolean mLowBitAmbient, showBitmap = true;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat dateFormat;

        Paint mBackgroundPaint, mDatePaint, mHighPaint;
        Paint mLowPaint, mHourPaint, mMinutePaint, mColonPaint;
        float mColonWidth;
        boolean mMute;

        int mWeatherIcon = -1;
        float mXOffset, mYOffset, mLineHeight;
        int mWhiteColor = R.color.white;

        String high, low;
        int weatherId;

        private AsyncTask<Void, Void, Integer> mLoadWeatherTask;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.y_offset);
            mLineHeight = resources.getDimension(R.dimen.line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.parseColor(getString(R.string.bg_color)));
            mDatePaint = createTextPaint(resources.getColor(R.color.date));
            mHourPaint = createTextPaint(resources.getColor(mWhiteColor));
            mMinutePaint = createTextPaint(resources.getColor(mWhiteColor));
            mColonPaint = createTextPaint(resources.getColor(mWhiteColor));
            mHighPaint = createTextPaint(resources.getColor(mWhiteColor), BOLD_TYPEFACE);
            mLowPaint = createTextPaint(resources.getColor(mWhiteColor));

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_WEATHER);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if(inAmbientMode) {
                mBackgroundPaint.setColor(Color.BLACK);
                showBitmap = false;
            } else {
                mBackgroundPaint.setColor(Color.parseColor(getString(R.string.bg_color)));
                showBitmap = true;
            }

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mDatePaint.setAntiAlias(antiAlias);
                mHighPaint.setAntiAlias(antiAlias);
                mLowPaint.setAntiAlias(antiAlias);
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
            mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mDatePaint.setAlpha(alpha);
                mHighPaint.setAlpha(alpha);
                mLowPaint.setAlpha(alpha);
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }


        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw the hours.
            float x = mXOffset;
            String hourString;
            hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));

            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);

            canvas.drawText(":", x, mYOffset, mColonPaint);
            x += mColonWidth;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);

            // Only render the day of week and date if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                // Day of week
                canvas.drawText(
                        dateFormat.format(mDate).toUpperCase(),
                        mXOffset, mYOffset + mLineHeight, mDatePaint);

                if(mWeatherIcon != -1 && showBitmap) {
                    Bitmap weatherBitmap = BitmapFactory.decodeResource(getResources(), mWeatherIcon);

                    if(weatherBitmap != null) {
                        canvas.drawBitmap(weatherBitmap, mXOffset - 15, (mYOffset + mLineHeight), null);
                    }
                }

                float hiLowX = mXOffset*3;
                if(high != null) {
                    canvas.drawText(
                            String.valueOf(high),
                            hiLowX, mYOffset + mLineHeight * 2, mHighPaint);
                }

                hiLowX += mHighPaint.measureText(String.valueOf(high)) + 15;

                if(low != null) {
                    canvas.drawText(
                            String.valueOf(low),
                            hiLowX, mYOffset + mLineHeight * 2, mLowPaint);
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();

                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
            } else {
                mUpdateTimeHandler.removeMessages(MSG_UPDATE_WEATHER);
                cancelLoadWeatherTask();
            }

            updateTimer();
        }

        private void cancelLoadWeatherTask() {
            if(mLoadWeatherTask != null) {
                mLoadWeatherTask.cancel(true);
            }
        }

        private void initFormats() {
            dateFormat = new SimpleDateFormat("E, MMM d y", Locale.getDefault());
            dateFormat.setCalendar(mCalendar);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.x_offset_round : R.dimen.x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.text_size_round : R.dimen.text_size);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mHighPaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mLowPaint.setTextSize(resources.getDimension(R.dimen.date_text_size));

            mColonWidth = mColonPaint.measureText(":");
        }

        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                    case MSG_UPDATE_WEATHER:
                        cancelLoadWeatherTask();
                        mLoadWeatherTask = new LoadWeatherTask();
                        mLoadWeatherTask.execute();
                        break;
                }
            }
        };


        private class LoadWeatherTask extends AsyncTask<Void, Void, Integer> {
            @Override
            protected Integer doInBackground(Void... voids) {

                Context context = getBaseContext();
                String locationQuery = WatchFaceUtil.getPreferredLocation(context);
                String locationLatitude = String.valueOf(WatchFaceUtil.getLocationLatitude(context));
                String locationLongitude = String.valueOf(WatchFaceUtil.getLocationLongitude(context));

                // These two need to be declared outside the try/catch
                // so that they can be closed in the finally block.
                HttpURLConnection urlConnection = null;
                BufferedReader reader = null;

                // Will contain the raw JSON response as a string.
                String forecastJsonStr = null;

                String format = "json";
                String units = "metric";
                int numDays = 1;

                try {
                    // Construct the URL for the OpenWeatherMap query
                    // Possible parameters are avaiable at OWM's forecast API page, at
                    // http://openweathermap.org/API#forecast
                    final String FORECAST_BASE_URL =
                            "http://api.openweathermap.org/data/2.5/forecast/daily?";
                    final String QUERY_PARAM = "q";
                    final String LAT_PARAM = "lat";
                    final String LON_PARAM = "lon";
                    final String FORMAT_PARAM = "mode";
                    final String UNITS_PARAM = "units";
                    final String DAYS_PARAM = "cnt";
                    final String APPID_PARAM = "APPID";

                    Uri.Builder uriBuilder = Uri.parse(FORECAST_BASE_URL).buildUpon();

                    // Instead of always building the query based off of the location string, we want to
                    // potentially build a query using a lat/lon value. This will be the case when we are
                    // syncing based off of a new location from the Place Picker API. So we need to check
                    // if we have a lat/lon to work with, and use those when we do. Otherwise, the weather
                    // service may not understand the location address provided by the Place Picker API
                    // and the user could end up with no weather! The horror!
                    if (WatchFaceUtil.isLocationLatLonAvailable(context)) {
                        uriBuilder.appendQueryParameter(LAT_PARAM, locationLatitude)
                                .appendQueryParameter(LON_PARAM, locationLongitude);
                    } else {
                        uriBuilder.appendQueryParameter(QUERY_PARAM, locationQuery);
                    }

                    Uri builtUri = uriBuilder.appendQueryParameter(FORMAT_PARAM, format)
                            .appendQueryParameter(UNITS_PARAM, units)
                            .appendQueryParameter(DAYS_PARAM, Integer.toString(numDays))
                            .appendQueryParameter(APPID_PARAM, OPEN_WEATHER_MAP_API_KEY)
                            .build();

                    URL url = new URL(builtUri.toString());

                    Log.i("DG", url.toString());

                    // Create the request to OpenWeatherMap, and open the connection
                    urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setRequestMethod("GET");
                    urlConnection.connect();

                    // Read the input stream into a String
                    InputStream inputStream = urlConnection.getInputStream();
                    StringBuffer buffer = new StringBuffer();
                    if (inputStream == null) {
                        // Nothing to do.
                        return -1;
                    }
                    reader = new BufferedReader(new InputStreamReader(inputStream));

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                        // But it does make debugging a *lot* easier if you print out the completed
                        // buffer for debugging.
                        buffer.append(line + "\n");
                    }

                    if (buffer.length() == 0) {
                        // Stream was empty.  No point in parsing.
                        setLocationStatus(context, LOCATION_STATUS_SERVER_DOWN);
                        return -1;
                    }
                    forecastJsonStr = buffer.toString();
                    getWeatherDataFromJson(forecastJsonStr);
                } catch (IOException e) {
                    Log.e("DG", "Error ", e);
                    // If the code didn't successfully get the weather data, there's no point in attempting
                    // to parse it.
                    setLocationStatus(context, LOCATION_STATUS_SERVER_DOWN);
                } catch (JSONException e) {
                    Log.e("DG", e.getMessage(), e);
                    e.printStackTrace();
                    setLocationStatus(context, LOCATION_STATUS_SERVER_INVALID);
                } finally {
                    if (urlConnection != null) {
                        urlConnection.disconnect();
                    }
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (final IOException e) {
                            Log.e("DG", "Error closing stream", e);
                        }
                    }
                }

                return 0;
            }

            @Override
            protected void onPostExecute(Integer result) {
                if(result != null && result == -1) {
                    low = "";
                    high = "";
                    weatherId = 0;
                } else {
                    mWeatherIcon = WatchFaceUtil.getIconResourceForWeatherCondition(weatherId);
                    invalidate();
                }
            }


        }

        /**
         * Sets the location status into shared preference.  This function should not be called from
         * the UI thread because it uses commit to write to the shared preferences.
         * @param c Context to get the PreferenceManager from.
         * @param locationStatus The IntDef value to set
         */
        private void setLocationStatus(Context c, @LocationStatus int locationStatus){
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(c);
            SharedPreferences.Editor spe = sp.edit();
            spe.putInt(c.getString(R.string.pref_location_status_key), locationStatus);
            spe.commit();
        }

        private void getWeatherDataFromJson(String forecastJsonStr)
                throws JSONException {

            try {
                JSONObject forecastJson = new JSONObject(forecastJsonStr);
                JSONArray weatherArray = forecastJson.getJSONArray(getString(R.string.list));
                JSONObject dayForecast = weatherArray.getJSONObject(0);
                JSONObject temperatureObject = dayForecast.getJSONObject(getString(R.string.temp));

                high = Double.toString(temperatureObject.getDouble(getString(R.string.max)));
                if(high != null && high.contains(".")) {
                    high = high.substring(0, high.indexOf('.'));
                }
                high = high + DEGREE;
                low = Double.toString(temperatureObject.getDouble(getString(R.string.min)));
                if(low != null && low.contains(".")) {
                    low = low.substring(0, low.indexOf('.'));
                }
                low = low + DEGREE;

                JSONObject weatherObject =
                        dayForecast.getJSONArray(getString(R.string.weather)).getJSONObject(0);
                weatherId = weatherObject.getInt(getString(R.string.id));
            }catch (JSONException e) {
                Log.e("DG", e.getMessage(), e);
                e.printStackTrace();
                setLocationStatus(getBaseContext(), LOCATION_STATUS_SERVER_INVALID);
            }
        }

    }
}
