package com.prath.carlauncher;

import android.service.notification.NotificationListenerService;

// ponytail: empty on purpose. Android only lets an app with an enabled notification
// listener read other apps' media sessions; Home does the actual work.
public class Listener extends NotificationListenerService {}
