package com.swarmnyc.watchfaces;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.util.TimeZone;

public class WeatherWatchFaceService extends CanvasWatchFaceService {
// ------------------------------ FIELDS ------------------------------

    private static final String TAG = "WeatherWatchFaceService";

// -------------------------- OTHER METHODS --------------------------

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

// -------------------------- INNER CLASSES --------------------------

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener,
            SensorEventListener {
// ------------------------------ FIELDS ------------------------------

        static final String COLON_STRING = ":";
        static final int MSG_UPDATE_TIME = 0;

        /**
         * Update rate in milliseconds for normal (not ambient and not mute) mode.
         * We update twice a second to blink the colons.
         */
        static final long UPDATE_RATE_MS = 500;
        AssetManager mAsserts;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    UPDATE_RATE_MS - (timeMs % UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mDateSuffixPaint;
        Paint mTemperatureBorderPaint;
        Paint mTemperaturePaint;
        Paint mTemperatureSuffixPaint;
        Paint mTimePaint;
        Resources mResources;
        SensorManager mSensorManager;
        Sensor mTemperatureSensor;
        String mNodeId = "SWARM WatchFaces";
        String mTemperatureUnit = ConverterUtil.FAHRENHEIT_STRING;
        String mWeatherCondition;
        Time mTime;
        boolean isRound;
        boolean mLowBitAmbient;
        boolean mRegisteredService = false;
        float mColonXOffset;
        float mDateSuffixYOffset;
        float mDateYOffset;
        float mInternalDistance;
        float mTemperature = Float.MAX_VALUE;
        float mTemperatureSuffixYOffset;
        float mTemperatureYOffset;
        float mTimeXOffset;
        float mTimeYOffset;
        boolean mGotConfig;

// ------------------------ INTERFACE METHODS ------------------------


// --------------------- Interface ConnectionCallbacks ---------------------

        @Override
        public void onConnected(Bundle bundle) {
            log("Connected: " + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            getConfig();
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

// --------------------- Interface DataListener ---------------------

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            log("DataChanged");

            for (DataEvent event : dataEvents) {
                DataMapItem item = DataMapItem.fromDataItem(event.getDataItem());
                DataMap config = item.getDataMap();

                log("DataChanged: " + config);

                fetchConfig(config);
            }
        }

// --------------------- Interface OnConnectionFailedListener ---------------------

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            log("ConnectionFailed");
        }

// --------------------- Interface SensorEventListener ---------------------

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mTemperatureUnit == ConverterUtil.CELSIUS_STRING) {
                mTemperature = event.values[0];
            } else {
                mTemperature = ConverterUtil.convertCelsiusToFahrenheit(event.values[0]);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

// -------------------------- OTHER METHODS --------------------------

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mTimePaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mTemperaturePaint.setAntiAlias(antiAlias);
                mTemperatureBorderPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            isRound = insets.isRound();

            mInternalDistance = mResources.getDimension(isRound ?
                    R.dimen.weather_internal_distance_round : R.dimen.weather_internal_distance);

            mTimeXOffset = mResources.getInteger(isRound ?
                    R.integer.weather_time_offset_round : R.integer.weather_time_offset);

            float timeTextSize = mResources.getDimension(isRound ?
                    R.dimen.weather_time_size_round : R.dimen.weather_time_size);

            float dateTextSize = mResources.getDimension(isRound ?
                    R.dimen.weather_date_size_round : R.dimen.weather_date_size);

            float dateSuffixTextSize = mResources.getDimension(isRound ?
                    R.dimen.weather_date_suffix_size_round : R.dimen.weather_date_suffix_size);

            float tempTextSize = mResources.getDimension(isRound ?
                    R.dimen.weather_temperature_size_round : R.dimen.weather_temperature_size);

            float tempSuffixTextSize = mResources.getDimension(isRound ?
                    R.dimen.weather_temperature_suffix_size_round : R.dimen.weather_temperature_suffix_size);

            mTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mDateSuffixPaint.setTextSize(dateSuffixTextSize);
            mTemperaturePaint.setTextSize(tempTextSize);
            mTemperatureSuffixPaint.setTextSize(tempSuffixTextSize);

            mColonXOffset = mTimePaint.measureText(COLON_STRING) / 2;
            mTimeYOffset = (mTimePaint.descent() + mTimePaint.ascent()) / 2;
            mDateYOffset = (mDatePaint.descent() + mDatePaint.ascent()) / 2;
            mDateSuffixYOffset = (mDateSuffixPaint.descent() + mDateSuffixPaint.ascent()) / 2;
            mTemperatureYOffset = (mTemperaturePaint.descent() + mTemperaturePaint.ascent()) / 2;
            mTemperatureSuffixYOffset = (mTemperatureSuffixPaint.descent() + mTemperatureSuffixPaint.ascent()) / 2;
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                            //.setHotwordIndicatorGravity(Gravity.RIGHT)
                    .setShowSystemUiTime(false)
                    .build());

            // Get an instance of the sensor service, and use that to get an instance of
            // a particular sensor.
            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            mTemperatureSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);

            mResources = WeatherWatchFaceService.this.getResources();
            mAsserts = WeatherWatchFaceService.this.getAssets();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mResources.getColor(R.color.weather_bg_color));

            mTemperatureBorderPaint = new Paint();
            mTemperatureBorderPaint.setStyle(Paint.Style.STROKE);
            mTemperatureBorderPaint.setColor(mResources.getColor(R.color.weather_temperature_border_color));
            mTemperatureBorderPaint.setStrokeWidth(3f);
            mTemperatureBorderPaint.setAntiAlias(true);

            Typeface timeFont = Typeface.createFromAsset(mAsserts, mResources.getString(R.string.weather_time_font));
            Typeface dateFont = Typeface.createFromAsset(mAsserts, mResources.getString(R.string.weather_date_font));
            Typeface tempFont = Typeface.createFromAsset(mAsserts, mResources.getString(R.string.weather_temperature_font));

            mTimePaint = createTextPaint(mResources.getColor(R.color.weather_time_color), timeFont);
            mDatePaint = createTextPaint(mResources.getColor(R.color.weather_date_color), dateFont);
            mDateSuffixPaint = createTextPaint(mResources.getColor(R.color.weather_date_color), dateFont);
            mTemperaturePaint = createTextPaint(mResources.getColor(R.color.weather_temperature_color), tempFont);
            mTemperatureSuffixPaint = createTextPaint(mResources.getColor(R.color.weather_temperature_color), tempFont);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            log("Draw");
            mTime.setToNow();

            int width = bounds.width();
            int height = bounds.height();
            float radius = width / 2;

            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // photo
            if (!TextUtils.isEmpty(mWeatherCondition)) {
                //TODO: Change to better code
                String name;
                if (mWeatherCondition.equals("weather_cloudy") || mWeatherCondition.equals("weather_clear")) {
                    //cloudy and clear have night picture
                    name = mWeatherCondition + (mTime.hour <= 6 || mTime.hour >= 18 ? "night" : "");
                } else {
                    name = mWeatherCondition;
                }

                int id = mResources.getIdentifier(name + (this.isInAmbientMode() ? "_gray" : ""), "drawable", WeatherWatchFaceService.class.getPackage().getName());

                Drawable b = mResources.getDrawable(id);
                Bitmap bb = ((BitmapDrawable) b).getBitmap();
                float sizeScale = (width * 0.5f) / bb.getWidth();
                bb = Bitmap.createScaledBitmap(bb, (int) (bb.getWidth() * sizeScale), (int) (bb.getHeight() * sizeScale), true);
                canvas.drawBitmap(bb, radius - bb.getWidth() / 2, 0, null);
            }

            // Time
            boolean mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;
            //boolean mShouldDrawColons = mTime.second % 2 ==0;

            String hourString = String.format("%02d", convertTo12Hour(mTime.hour));
            String minString = String.format("%02d", mTime.minute);

            //Test number
            //hourString = "07";
            //minString = "30";
            //mTemperature = 50;

            float hourWidth = mTimePaint.measureText(hourString);

            float x = radius - hourWidth - mColonXOffset + mTimeXOffset;
            float y = radius - mTimeYOffset;
            float suffixY;

            canvas.drawText(hourString, x, y, mTimePaint);

            x = radius - mColonXOffset + mTimeXOffset;

            if (isInAmbientMode() || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, y, mTimePaint);
            }

            x = radius + mColonXOffset + mTimeXOffset;
            canvas.drawText(minString, x, y, mTimePaint);

            suffixY = y + mDateSuffixPaint.getTextSize() +
                    mInternalDistance +
                    mDateSuffixYOffset;

            y += mDatePaint.getTextSize() + mInternalDistance + mDateYOffset;

            //Date
            String monthString = convertToMonth(mTime.month);
            String dayString = String.valueOf(mTime.monthDay);
            String daySuffixString = getDaySuffix(mTime.monthDay);

            float monthWidth = mDatePaint.measureText(monthString);
            float dayWidth = mDatePaint.measureText(dayString);
            float dateWidth = monthWidth + dayWidth +
                    mDateSuffixPaint.measureText(daySuffixString);

            x = radius - dateWidth / 2;
            canvas.drawText(monthString, x, y, mDatePaint);
            x += monthWidth;
            canvas.drawText(dayString, x, y, mDatePaint);
            x += dayWidth;
            canvas.drawText(daySuffixString, x, suffixY, mDateSuffixPaint);

            //temperature
            if (mTemperature != Float.MAX_VALUE) {
                String temperatureString = String.valueOf((int) mTemperature);
                float temperatureWidth = mTemperaturePaint.measureText(temperatureString);
                float temperatureRadius = (temperatureWidth + mTemperatureSuffixPaint.measureText(mTemperatureUnit)) / 2;
                float borderPadding = temperatureRadius * 0.5f;
                x = radius;
                y = bounds.height() * 0.80f;
                suffixY = y - mTemperatureSuffixYOffset;
                canvas.drawCircle(radius, y + borderPadding / 2, temperatureRadius + borderPadding, mTemperatureBorderPaint);

                x -= temperatureRadius;
                y -= mTemperatureYOffset;
                canvas.drawText(temperatureString, x, y, mTemperaturePaint);
                x += temperatureWidth;
                canvas.drawText(mTemperatureUnit, x, suffixY, mTemperatureSuffixPaint);
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);

            log("onInterruptionFilterChanged: " + interruptionFilter);
            //boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            log("onPropertiesChanged: LowBitAmbient=" + mLowBitAmbient);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            log("TimeTick");
            invalidate();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            log("onVisibilityChanged: " + visible);


            if (visible) {
                mGoogleApiClient.connect();

                registerService();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterService();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private Paint createTextPaint(int color, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private String convertToMonth(int month) {
            switch (month) {
                case 0:
                    return "January ";
                case 1:
                    return "February ";
                case 2:
                    return "March ";
                case 3:
                    return "April ";
                case 4:
                    return "May ";
                case 5:
                    return "June ";
                case 6:
                    return "July ";
                case 7:
                    return "August ";
                case 8:
                    return "September ";
                case 9:
                    return "October ";
                case 10:
                    return "November ";
                case 11:
                    return "October ";
                default:
                    return "December";
            }
        }

        private String getDaySuffix(int monthDay) {
            switch (monthDay) {
                case 1:
                    return "st";
                case 2:
                    return "nd";
                case 3:
                    return "rd";
                default:
                    return "th";
            }
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private int convertTo12Hour(int hour) {
            int result = hour % 12;
            return (result == 0) ? 12 : result;
        }

        private void getConfig() {
           /* if (mGotConfig){
                return;
            }*/

            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetLocalNodeResult>() {
                @Override
                public void onResult(NodeApi.GetLocalNodeResult getLocalNodeResult) {
                    String localNode = getLocalNodeResult.getNode().getId();
                    Uri uri = new Uri.Builder()
                            .scheme("wear")
                            .path("/WeatherWatchFace")
                            .authority(localNode)
                            .build();


                    /*Wearable.DataApi.getDataItem(mGoogleApiClient, uri).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            log("GotConfig");
                            if (dataItemResult.getStatus().isSuccess()) {
                                mGotConfig = true;
                                if (dataItemResult.getDataItem() != null) {
                                    DataItem configDataItem = dataItemResult.getDataItem();
                                    DataMapItem dataMapItem = DataMapItem.fromDataItem(configDataItem);
                                    DataMap config = dataMapItem.getDataMap();
                                    fetchConfig(config);
                                }
                            }
                        }
                    });*/

                    Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {
                        @Override
                        public void onResult(DataItemBuffer dataItems) {
                            //TODO: To Find out why uri is different
                            if (dataItems.getStatus().isSuccess()) {
                                for (DataItem item : dataItems) {
                                    if (item.getUri().getPath().endsWith("/WeatherWatchFace")) {
                                        fetchConfig(DataMapItem.fromDataItem(item).getDataMap());
                                    }
                                }
                            }

                            dataItems.release();
                        }
                    });
                }
            });
        }

        private void log(String message) {
            //if (Log.isLoggable(TAG, Log.DEBUG))
            //{
            Log.d(TAG, message);
            //}
        }

        private void fetchConfig(DataMap config) {
            if (config.containsKey("Temperature")) {
                mTemperature = config.getInt("Temperature");
            }

            if (config.containsKey("Condition")) {
                String cond = config.getString("Condition");
                if (TextUtils.isEmpty(cond)) {
                    mWeatherCondition = null;
                } else {
                    mWeatherCondition = "weather_" + cond;
                }
            }
        }

        private void registerService() {
            //TimeZone and TemperatureSensor
            if (mRegisteredService) {
                return;
            }

            mRegisteredService = true;

            // TimeZone
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);

            //Temperature
            if (mTemperatureSensor != null)
                mSensorManager.registerListener(this, mTemperatureSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        private void unregisterService() {
            if (!mRegisteredService) {
                return;
            }
            mRegisteredService = false;

            //TimeZone
            WeatherWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);

            //Temperature
            if (mTemperatureSensor != null)
                mSensorManager.unregisterListener(this);
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }
    }
}