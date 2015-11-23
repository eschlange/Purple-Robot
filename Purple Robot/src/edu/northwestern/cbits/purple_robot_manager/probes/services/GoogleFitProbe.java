package edu.northwestern.cbits.purple_robot_manager.probes.services;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.data.Value;
import com.google.android.gms.fitness.request.DataSourcesRequest;
import com.google.android.gms.fitness.request.OnDataPointListener;
import com.google.android.gms.fitness.request.SensorRequest;
import com.google.android.gms.fitness.result.DataSourcesResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import edu.northwestern.cbits.purple_robot_manager.db.ProbeValuesProvider;
import edu.northwestern.cbits.purple_robot_manager.logging.LogManager;
import edu.northwestern.cbits.purple_robot_manager.probes.Probe;
import edu.northwestern.cbits.purple_robot_manager.R;
import edu.northwestern.cbits.xsi.oauth.FitbitApi;
import edu.northwestern.cbits.xsi.oauth.Keystore;

public class GoogleFitProbe extends Probe implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    public static final String NAME = "edu.northwestern.cbits.purple_robot_manager.probes.services.GoogleFitProbe";
    public static final String CLASS_NAME = GoogleFitProbe.class.getName();
    public static final String DB_TABLE = "google_fit_probe";
    public static final boolean DEFAULT_ENABLE_CALIBRATION_NOTIFICATIONS = true;
    public static final String ENABLE_CALIBRATION_NOTIFICATIONS = "config_probe_google_fit_calibration_notifications";

    public static String ENABLED = "config_probe_google_fit_enabled";
    public static String FREQUENCY = "config_probe_google_fit_frequency";

    public static final boolean DEFAULT_ENABLED = false;

    protected Context _context = null;

    private static final String STEPS_KEY = "STEPS";
    private long _lastFrequency = 0;
    private boolean _listening = false;
    private final HashMap<String, Boolean> _lastEnabled = new HashMap<>();
    private final HashMap<String, Integer> _lastStatus = new HashMap<>();
    private GoogleApiClient _client = null;
    private OnDataPointListener _listener;

    private boolean authInProgress = false;
    private long _probeEnabled = 0;
    private long _lastReading = 0;

    @Override
    public String probeCategory(Context context) {
        return context.getString(R.string.probe_external_services_category);
    }

    @Override
    public void enable(Context context) {
        SharedPreferences prefs = Probe.getPreferences(context);
        SharedPreferences.Editor e = prefs.edit();

        e.putBoolean(GoogleFitProbe.ENABLED, true);
        e.commit();
    }

    @Override
    public void disable(Context context) {
        SharedPreferences prefs = Probe.getPreferences(context);
        SharedPreferences.Editor e = prefs.edit();

        e.putBoolean(GoogleFitProbe.ENABLED, false);
        e.commit();
    }

    @Override
    public Map<String, Object> configuration(Context context) {
        Map<String, Object> map = super.configuration(context);
        SharedPreferences prefs = Probe.getPreferences(context);
        long freq = Long.parseLong(prefs.getString(GoogleFitProbe.FREQUENCY, Probe.DEFAULT_FREQUENCY));

        map.put(Probe.PROBE_FREQUENCY, freq);
        return map;
    }

    @Override
    public void updateFromMap(Context context, Map<String, Object> params) {
        super.updateFromMap(context, params);

        if (params.containsKey(Probe.PROBE_FREQUENCY)) {
            Object frequency = params.get(Probe.PROBE_FREQUENCY);

            if (frequency instanceof Double) {
                frequency = ((Double) frequency).longValue();
            }

            if (frequency instanceof Long) {
                SharedPreferences prefs = Probe.getPreferences(context);
                SharedPreferences.Editor e = prefs.edit();

                e.putString(GoogleFitProbe.FREQUENCY, frequency.toString());
                e.commit();
            }
        }

        if (params.containsKey(Probe.PROBE_CALIBRATION_NOTIFICATIONS)) {
            Object enable = params.get(Probe.PROBE_CALIBRATION_NOTIFICATIONS);

            if (enable instanceof Boolean) {
                SharedPreferences prefs = Probe.getPreferences(context);
                SharedPreferences.Editor e = prefs.edit();

                e.putBoolean(GoogleFitProbe.ENABLE_CALIBRATION_NOTIFICATIONS, ((Boolean) enable));
                e.commit();
            }
        }

    }

    @Override
    public String summary(Context context) {
        return context.getString(R.string.summary_google_fitness_probe_desc);
    }

    @Override
    @SuppressWarnings("deprecation")
    public PreferenceScreen preferenceScreen(final Context context, PreferenceManager manager) {
        PreferenceScreen screen = manager.createPreferenceScreen(context);
        screen.setTitle(this.title(context));
        screen.setSummary(R.string.summary_google_fitness_probe_desc);

        CheckBoxPreference enabled = new CheckBoxPreference(context);
        enabled.setTitle(R.string.title_enable_probe);
        enabled.setKey(GoogleFitProbe.ENABLED);
        enabled.setDefaultValue(GoogleFitProbe.DEFAULT_ENABLED);

        screen.addPreference(enabled);

        return screen;
    }

    @Override
    public JSONObject fetchSettings(Context context)
    {
        JSONObject settings = new JSONObject();

        try {
            JSONObject enabled = new JSONObject();
            enabled.put(Probe.PROBE_TYPE, Probe.PROBE_TYPE_BOOLEAN);
            JSONArray values = new JSONArray();
            values.put(true);
            values.put(false);
            enabled.put(Probe.PROBE_VALUES, values);
            settings.put(Probe.PROBE_ENABLED, enabled);

            settings.put(Probe.PROBE_CALIBRATION_NOTIFICATIONS, enabled);

            JSONObject frequency = new JSONObject();
            frequency.put(Probe.PROBE_TYPE, Probe.PROBE_TYPE_LONG);
        } catch (JSONException e) {
            LogManager.getInstance(context).logException(e);
        }

        return settings;
    }

    @Override
    public boolean isEnabled(final Context context) {
        final SharedPreferences prefs = Probe.getPreferences(context);

        if (this._context == null) {
            this._context = context.getApplicationContext();
        }

        if (super.isEnabled(context) && prefs.getBoolean(GoogleFitProbe.ENABLED, GoogleFitProbe.DEFAULT_ENABLED)) {

            long now = System.currentTimeMillis();
            if (this._probeEnabled == 0)
                this._probeEnabled = now;

            this._context = context.getApplicationContext();
            long interval = Long.parseLong(prefs.getString(GoogleFitProbe.FREQUENCY, Probe.DEFAULT_FREQUENCY));
            if (interval != this._lastFrequency && _client != null && _client.isConnected())
            {
                this._lastFrequency = interval;

                if (this._client.isConnected())
                {
                    try {
                        // TODO: transmit payload
                    }
                    catch (NullPointerException e)
                    {
                        LogManager.getInstance(context).logException(e);
                    }

                    this._client.disconnect();
                }

                this._client.unregisterConnectionCallbacks(this);
                this._client.unregisterConnectionFailedListener(this);
                this._client = null;
            }

            if (this._client == null) {
                this._client = new GoogleApiClient.Builder(this._context)
                        .addApi(Fitness.SENSORS_API)
                        .addScope(new Scope(Scopes.FITNESS_LOCATION_READ))
                        .addScope(new Scope(Scopes.FITNESS_ACTIVITY_READ))
                        .addScope(new Scope(Scopes.FITNESS_BODY_READ))
                        .addConnectionCallbacks(
                                new GoogleApiClient.ConnectionCallbacks() {

                                    @Override
                                    public void onConnected(Bundle bundle) {
                                        Log.i(CLASS_NAME, "GOOGLE FITNESS: Connected!!!");
                                        findFitnessDataSources();
                                    }

                                    @Override
                                    public void onConnectionSuspended(int i) {
                                        if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST) {
                                            Log.i(CLASS_NAME, "GOOGLE FITNESS: Network connection lost.");
                                        } else if (i == GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED) {
                                            Log.i(CLASS_NAME, "GOOGLE FITNESS: Disconnected.");
                                        }
                                    }
                                }
                        )
                        .addOnConnectionFailedListener(this).build();

                this._client.connect();
            }

            return true;
        } else if(_client != null && _client.isConnected()) {
            this._probeEnabled = 0;
            this._client.unregisterConnectionCallbacks(this);
            this._client.unregisterConnectionFailedListener(this);
            this._client.disconnect();
            this._client = null;
        }

        this._listening = false;
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_OAUTH) {
            authInProgress = false;
            if (resultCode == RESULT_OK) {
                // Make sure the app is not already connected or attempting to connect
                if (!mClient.isConnecting() && !mClient.isConnected()) {
                    mClient.connect();
                }
            }
        }
    }

    /**
     * Find available data sources and attempt to register on a specific {@link DataType}.
     * If the application cares about a data type but doesn't care about the source of the data,
     * this can be skipped entirely, instead calling
     *     {@link com.google.android.gms.fitness.SensorsApi
     *     #register(GoogleApiClient, SensorRequest, DataSourceListener)},
     * where the {@link SensorRequest} contains the desired data type.
     */
    private void findFitnessDataSources() {
        Fitness.SensorsApi.findDataSources(_client, new DataSourcesRequest.Builder()
                .setDataTypes(
                        DataType.TYPE_ACTIVITY_SAMPLE,
                        DataType.TYPE_ACTIVITY_SEGMENT,
                        DataType.TYPE_BASAL_METABOLIC_RATE,
                        DataType.TYPE_BODY_FAT_PERCENTAGE,
                        DataType.TYPE_CALORIES_CONSUMED,
                        DataType.TYPE_CALORIES_EXPENDED,
                        DataType.TYPE_CYCLING_PEDALING_CADENCE,
                        DataType.TYPE_CYCLING_PEDALING_CUMULATIVE,
                        DataType.TYPE_CYCLING_WHEEL_REVOLUTION,
                        DataType.TYPE_CYCLING_WHEEL_RPM,
                        DataType.TYPE_DISTANCE_CUMULATIVE,
                        DataType.TYPE_DISTANCE_DELTA,
                        DataType.TYPE_HEART_RATE_BPM,
                        DataType.TYPE_LOCATION_SAMPLE,
                        DataType.TYPE_LOCATION_TRACK,
                        DataType.TYPE_STEP_COUNT_CADENCE,
                        DataType.TYPE_STEP_COUNT_CUMULATIVE,
                        DataType.TYPE_STEP_COUNT_DELTA)
                .setDataSourceTypes(DataSource.TYPE_RAW)
                .build())
                .setResultCallback(new ResultCallback<DataSourcesResult>() {
                    @Override
                    public void onResult(DataSourcesResult dataSourcesResult) {
                        Log.i(CLASS_NAME, "Result: " + dataSourcesResult.getStatus().toString());

                        for (DataSource dataSource : dataSourcesResult.getDataSources()) {
                            // START: Register data source listeners for payload data transcription
                            Log.i(CLASS_NAME, "Data source found: " + dataSource.toString());
                            Log.i(CLASS_NAME, "Data Source type: " + dataSource.getDataType().getName());

                            if (dataSource.getDataType().equals(DataType.TYPE_LOCATION_SAMPLE) && _listener == null) {
                                Log.i(CLASS_NAME, "Data source for LOCATION_SAMPLE found!  Registering.");
                                registerFitnessDataListener(dataSource, DataType.TYPE_LOCATION_SAMPLE);
                            }

                            if (dataSource.getDataType().equals(DataType.TYPE_STEP_COUNT_CUMULATIVE) && _listener == null) {
                                Log.i(CLASS_NAME, "Data source for TYPE_STEP_COUNT_CUMULATIVE found!  Registering.");
                                registerFitnessDataListener(dataSource, DataType.TYPE_STEP_COUNT_CUMULATIVE);
                            }
                            // END: Register data source listeners for payload data transcription
                        }
                    }
                });
    }

    /**
     * Register a listener with the Sensors API for the provided {@link DataSource} and
     * {@link DataType} combo.
     */
    private void registerFitnessDataListener(DataSource dataSource, DataType dataType) {
        _listener = new OnDataPointListener() {
            @Override
            public void onDataPoint(DataPoint dataPoint) {
                for (Field field : dataPoint.getDataType().getFields()) {
                    Value val = dataPoint.getValue(field);
                    Log.i(CLASS_NAME, "Detected DataPoint field: " + field.getName());
                    Log.i(CLASS_NAME, "Detected DataPoint value: " + val);
                }
                // TODO: transmit payload
            }
        };

        Fitness.SensorsApi.add(
                _client,
                new SensorRequest.Builder()
                        .setDataSource(dataSource) // Optional but recommended for custom data sets.
                        .setDataType(dataType) // Can't be omitted.
                        .setSamplingRate(10, TimeUnit.SECONDS)
                        .build(),
                _listener)
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        if (status.isSuccess()) {
                            Log.i(CLASS_NAME, "Listener registered!");
                        } else {
                            Log.i(CLASS_NAME, "Listener not registered.");
                        }
                    }
                });
    }

    /**
     * Unregister the listener with the Sensors API.
     */
    private void unregisterFitnessDataListener() {
        if (_listener == null) {
            return;
        }

        Fitness.SensorsApi.remove(
                _client,
                _listener)
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        if (status.isSuccess()) {
                            Log.i(CLASS_NAME, "Listener was removed!");
                        } else {
                            Log.i(CLASS_NAME, "Listener was not removed.");
                        }
                    }
                });
    }

    @Override
    public String title(Context context) {
        return context.getString(R.string.title_google_fit_probe);
    }

    @Override
    public String name(Context context) {
        return GoogleFitProbe.NAME;
    }

    @Override
    public String summarizeValue(Context context, Bundle bundle)
    {
        // TODO: determine summarized value contents
        return "<google fit data summary>";
    }

    @Override
    public void onConnected(Bundle bundle)
    {
        //TODO: connection logic
    }

    @Override
    public void onConnectionSuspended(int i) {
        //TODO: shut down client?
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        this._client = null;
    }

    public static Map<String, String> databaseSchema()
    {
        HashMap<String, String> schema = new HashMap<>();
        schema.put(GoogleFitProbe.STEPS_KEY, ProbeValuesProvider.REAL_TYPE);
        return schema;
    }

    @Override
    public Intent viewIntent(Context context) {
        try {
            // TODO: build intent
            return super.viewIntent(context);
        } catch (Exception e) {
            return super.viewIntent(context);
        }
    }
}
