package edu.northwestern.cbits.purple_robot_manager.probes.builtin;

import java.util.Map;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.support.v4.content.LocalBroadcastManager;
import edu.northwestern.cbits.purple_robot_manager.R;
import edu.northwestern.cbits.purple_robot_manager.probes.Probe;

public abstract class ContinuousProbe extends Probe
{
	public static final String WAKE_ACTION = "purple_robot_sensor_wake";

	protected static final String PROBE_THRESHOLD = "threshold";

	private static SharedPreferences prefs = null;

	private PendingIntent _intent = null;
	
	protected static SharedPreferences getPreferences(Context context)
	{
		if (ContinuousProbe.prefs == null)
			ContinuousProbe.prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

		return ContinuousProbe.prefs;
	}

	protected Context _context = null;

	public void enable(Context context)
	{
		String key = this.getPreferenceKey();

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		
		Editor e = prefs.edit();
		e.putBoolean("config_probe_" + key + "_enabled", true);
		
		e.commit();
	}

	public void disable(Context context)
	{
		String key = this.getPreferenceKey();

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		
		Editor e = prefs.edit();
		e.putBoolean("config_probe_" + key + "_enabled", false);
		
		e.commit();
	}

	public PreferenceScreen preferenceScreen(PreferenceActivity activity)
	{
		PreferenceManager manager = activity.getPreferenceManager();

		PreferenceScreen screen = manager.createPreferenceScreen(activity);
		screen.setTitle(this.title(activity));
		screen.setSummary(this.summary(activity));

		String key = this.getPreferenceKey();

		CheckBoxPreference enabled = new CheckBoxPreference(activity);
		enabled.setTitle(R.string.title_enable_probe);
		enabled.setKey("config_probe_" + key + "_enabled");
		enabled.setDefaultValue(false);

		screen.addPreference(enabled);

		ListPreference duration = new ListPreference(activity);
		duration.setKey("config_probe_" + key + "_frequency");
		duration.setDefaultValue("1000");
		duration.setEntryValues(this.getResourceFrequencyValues());
		duration.setEntries(this.getResourceFrequencyLabels());
		duration.setTitle(R.string.probe_frequency_label);

		screen.addPreference(duration);

		return screen;
	}

	public Map<String, Object> configuration(Context context)
	{
		Map<String, Object> map = super.configuration(context);
		
		map.put(Probe.PROBE_FREQUENCY, this.getFrequency());
		
		return map;
	}
	
	public void updateFromMap(Context context, Map<String, Object> params) 
	{
		super.updateFromMap(context, params);
		
		if (params.containsKey(Probe.PROBE_FREQUENCY))
		{
			Object frequency = params.get(Probe.PROBE_FREQUENCY);
			
			if (frequency instanceof Long)
			{
				String key = "config_probe_" + this.getPreferenceKey() + "_frequency";
				
				SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
				Editor e = prefs.edit();
				
				e.putString(key, frequency.toString());
				e.commit();
			}
		}
	}

	public Bundle formattedBundle(Context context, Bundle bundle)
	{
		Bundle formatted = super.formattedBundle(context, bundle);

		return formatted;
	};

	public void updateFromJSON(Context context, JSONObject json) throws JSONException
	{
		// TODO...
	}

	public abstract int getResourceFrequencyLabels();
	public abstract int getResourceFrequencyValues();

	public abstract int getTitleResource();
	public abstract int getSummaryResource();

	public abstract int getCategoryResource();
	public abstract long getFrequency();
	public abstract String getPreferenceKey();

	protected abstract boolean passesThreshold(SensorEvent event);

	public void onAccuracyChanged(Sensor sensor, int accuracy)
	{

	}

	public String title(Context context)
	{
		return context.getString(this.getTitleResource());
	}

	public String summary(Context context)
	{
		return context.getString(this.getSummaryResource());
	}

	public String probeCategory(Context context)
	{
		return context.getResources().getString(this.getCategoryResource());
	}

	protected void transmitData(Bundle data)
	{
		if (this._context != null)
		{
			UUID uuid = UUID.randomUUID();
			data.putString("GUID", uuid.toString());

			LocalBroadcastManager localManager = LocalBroadcastManager.getInstance(this._context);
			Intent intent = new Intent(edu.northwestern.cbits.purple_robot_manager.probes.Probe.PROBE_READING);
			intent.putExtras(data);

			localManager.sendBroadcast(intent);
		}
	}
	
	public boolean isEnabled(Context context)
	{
		boolean enabled = super.isEnabled(context);

		if (enabled)
		{
			if (this._intent == null)
			{
				this._intent = PendingIntent.getService(context, 0, new Intent(ContinuousProbe.WAKE_ACTION), PendingIntent.FLAG_UPDATE_CURRENT);

				AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
				
				am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 250, this._intent);
			}
		}
		else if (this._intent != null)
		{
			AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
			
			am.cancel(this._intent);
			
			this._intent = null;
		}
		
		return enabled;
	}
}
