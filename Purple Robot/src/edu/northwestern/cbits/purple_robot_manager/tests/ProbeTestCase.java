package edu.northwestern.cbits.purple_robot_manager.tests;

import android.content.Context;
import edu.northwestern.cbits.purple_robot_manager.R;

public class ProbeTestCase extends RobotTestCase 
{
	public ProbeTestCase(Context context, int priority) 
	{
		super(context, priority);
	}
	
	public void test() 
	{

	}

	public String name(Context context) 
	{
		return context.getString(R.string.name_probe_test);
	}
}