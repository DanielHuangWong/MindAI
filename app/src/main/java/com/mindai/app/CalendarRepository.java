package com.mindai.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

public final class CalendarRepository {
    private final Context context;
    private final SharedPreferences settings;

    public CalendarRepository(Context context) {
        this.context = context.getApplicationContext();
        this.settings = context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE);
    }

    public boolean canRead() {
        return ComplianceSettings.hasCalendarDisclosureConsent(context)
                && context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean canWrite() {
        return ComplianceSettings.hasCalendarDisclosureConsent(context)
                && context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    public List<Models.CalendarSource> calendars() {
        List<Models.CalendarSource> result = new ArrayList<>();
        if (!canRead()) return result;
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        };
        try (Cursor c = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                projection, null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")) {
            if (c == null) return result;
            while (c.moveToNext()) {
                Models.CalendarSource source = new Models.CalendarSource();
                source.id = c.getLong(0);
                source.name = safe(c.getString(1));
                source.accountName = safe(c.getString(2));
                source.accountType = safe(c.getString(3));
                source.color = c.getInt(4);
                source.visible = c.getInt(5) != 0;
                source.writable = c.getInt(6) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                result.add(source);
            }
        } catch (SecurityException ignored) {}
        return result;
    }

    public List<Models.CalendarEvent> events(long start, long end) {
        List<Models.CalendarEvent> result = new ArrayList<>();
        if (!canRead() || end <= start) return result;
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, start);
        ContentUris.appendId(builder, end);
        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.CALENDAR_ACCESS_LEVEL
        };
        Set<String> excluded = new HashSet<>(settings.getStringSet("excluded_calendar_ids", new HashSet<>()));
        try (Cursor c = context.getContentResolver().query(builder.build(), projection,
                null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return result;
            while (c.moveToNext()) {
                long calendarId = c.getLong(1);
                if (excluded.contains(String.valueOf(calendarId))) continue;
                Models.CalendarEvent event = new Models.CalendarEvent();
                event.id = c.getLong(0);
                event.calendarId = calendarId;
                event.calendarName = safe(c.getString(2));
                event.title = safe(c.getString(3));
                event.description = safe(c.getString(4));
                event.location = safe(c.getString(5));
                event.startAt = c.getLong(6);
                event.endAt = c.getLong(7);
                event.allDay = c.getInt(8) != 0;
                int availability = c.getInt(9);
                event.availability = availability == CalendarContract.Events.AVAILABILITY_FREE ? "free"
                        : availability == CalendarContract.Events.AVAILABILITY_TENTATIVE ? "tentative" : "busy";
                event.readOnly = c.getInt(10) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                event.instanceKey = event.id + ":" + event.startAt;
                result.add(event);
            }
        } catch (SecurityException ignored) {}
        return result;
    }

    public Models.CalendarEvent event(long eventId, long instanceStart) {
        long start = Math.max(0, instanceStart - 86_400_000L);
        long end = instanceStart + 86_400_000L;
        for (Models.CalendarEvent event : events(start, end)) {
            if (event.id == eventId && (instanceStart <= 0 || event.startAt == instanceStart)) return event;
        }
        return null;
    }

    public long create(String title, String description, String location, long startAt, long endAt,
                       boolean allDay, long requestedCalendarId) {
        if (!canWrite()) throw new IllegalStateException("需要日历写入权限");
        long calendarId = requestedCalendarId > 0 ? requestedCalendarId : defaultWritableCalendarId();
        if (calendarId <= 0) throw new IllegalStateException("没有找到可写入的系统日历");
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title == null ? "" : title.trim());
        values.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description.trim());
        values.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location.trim());
        values.put(CalendarContract.Events.DTSTART, startAt);
        values.put(CalendarContract.Events.DTEND, Math.max(startAt + 60_000L, endAt));
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        Uri uri = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("系统日历没有返回创建结果");
        return ContentUris.parseId(uri);
    }

    public void update(long eventId, String title, String description, String location,
                       long startAt, long endAt, boolean allDay) {
        if (!canWrite()) throw new IllegalStateException("需要日历写入权限");
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.TITLE, title == null ? "" : title.trim());
        values.put(CalendarContract.Events.DESCRIPTION, description == null ? "" : description.trim());
        values.put(CalendarContract.Events.EVENT_LOCATION, location == null ? "" : location.trim());
        values.put(CalendarContract.Events.DTSTART, startAt);
        values.put(CalendarContract.Events.DTEND, Math.max(startAt + 60_000L, endAt));
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        int changed = context.getContentResolver().update(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, eventId), values, null, null);
        if (changed <= 0) throw new IllegalStateException("系统日历未能保存修改");
    }

    public void delete(long eventId) {
        if (!canWrite()) throw new IllegalStateException("需要日历写入权限");
        int changed = context.getContentResolver().delete(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, eventId), null, null);
        if (changed <= 0) throw new IllegalStateException("系统日历未能删除日程");
    }

    public long defaultWritableCalendarId() {
        long preferred = settings.getLong("default_calendar_id", 0);
        long first = 0;
        for (Models.CalendarSource source : calendars()) {
            if (!source.visible || !source.writable) continue;
            if (source.id == preferred) return preferred;
            if (first == 0) first = source.id;
        }
        return first;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
