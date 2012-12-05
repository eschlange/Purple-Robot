package edu.northwestern.cbits.purple_robot_manager.charts;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.app.Activity;
import edu.northwestern.cbits.purple_robot_manager.activities.WebkitActivity;

public class LineChart extends Chart
{
	protected Map<String, List<Double>> _series = new HashMap<String, List<Double>>();

	public void addSeries(String key, List<Double> series)
	{
		this._series.put(key, series);
	}

	public JSONObject highchartsJson(Activity activity) throws JSONException, IOException
	{
		JSONObject chartJson = (JSONObject) new JSONTokener(WebkitActivity.stringForAsset(activity, "webkit/js/highcharts_line.js")).nextValue();

		JSONArray series = chartJson.getJSONArray("series");

		for (String key : this._series.keySet())
		{
			JSONObject seriesObject = new JSONObject();

			seriesObject.put("name", key);

			JSONArray array = new JSONArray();

			List<Double> list = this._series.get(key);

			for (Double d : list)
			{
				array.put(d.doubleValue());
			}

			seriesObject.put("data", array);

			series.put(seriesObject);
		}

		return chartJson;
	}
}
