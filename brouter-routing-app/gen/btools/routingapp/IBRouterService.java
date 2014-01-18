/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Original file: C:\\brouter\\brouter-routing-app\\src\\main\\java\\btools\\routingapp\\IBRouterService.aidl
 */
package btools.routingapp;
public interface IBRouterService extends android.os.IInterface
{
/** Local-side IPC implementation stub class. */
public static abstract class Stub extends android.os.Binder implements btools.routingapp.IBRouterService
{
private static final java.lang.String DESCRIPTOR = "btools.routingapp.IBRouterService";
/** Construct the stub at attach it to the interface. */
public Stub()
{
this.attachInterface(this, DESCRIPTOR);
}
/**
 * Cast an IBinder object into an btools.routingapp.IBRouterService interface,
 * generating a proxy if needed.
 */
public static btools.routingapp.IBRouterService asInterface(android.os.IBinder obj)
{
if ((obj==null)) {
return null;
}
android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
if (((iin!=null)&&(iin instanceof btools.routingapp.IBRouterService))) {
return ((btools.routingapp.IBRouterService)iin);
}
return new btools.routingapp.IBRouterService.Stub.Proxy(obj);
}
@Override public android.os.IBinder asBinder()
{
return this;
}
@Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
{
switch (code)
{
case INTERFACE_TRANSACTION:
{
reply.writeString(DESCRIPTOR);
return true;
}
case TRANSACTION_getTrackFromParams:
{
data.enforceInterface(DESCRIPTOR);
android.os.Bundle _arg0;
if ((0!=data.readInt())) {
_arg0 = android.os.Bundle.CREATOR.createFromParcel(data);
}
else {
_arg0 = null;
}
java.lang.String _result = this.getTrackFromParams(_arg0);
reply.writeNoException();
reply.writeString(_result);
return true;
}
}
return super.onTransact(code, data, reply, flags);
}
private static class Proxy implements btools.routingapp.IBRouterService
{
private android.os.IBinder mRemote;
Proxy(android.os.IBinder remote)
{
mRemote = remote;
}
@Override public android.os.IBinder asBinder()
{
return mRemote;
}
public java.lang.String getInterfaceDescriptor()
{
return DESCRIPTOR;
}
//param params--> Map of params:
//  "pathToFileResult"-->String with the path to where the result must be saved, including file name and extension
//                    -->if null, the track is passed via the return argument
//  "maxRunningTime"-->String with a number of seconds for the routing timeout, default = 60
//  "trackFormat"-->[kml|gpx] default = gpx
//  "lats"-->double[] array of latitudes; 2 values at least.
//  "lons"-->double[] array of longitudes; 2 values at least.
//  "nogoLats"-->double[] array of nogo latitudes; may be null.
//  "nogoLons"-->double[] array of nogo longitudes; may be null.
//  "nogoRadi"-->double[] array of nogo radius in meters; may be null.
//  "fast"-->[0|1]
//  "v"-->[motorcar|bicycle|foot]
//return null if all ok and no path given, the track if ok and path given, an error message if it was wrong
//call in a background thread, heavy task!

@Override public java.lang.String getTrackFromParams(android.os.Bundle params) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
java.lang.String _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
if ((params!=null)) {
_data.writeInt(1);
params.writeToParcel(_data, 0);
}
else {
_data.writeInt(0);
}
mRemote.transact(Stub.TRANSACTION_getTrackFromParams, _data, _reply, 0);
_reply.readException();
_result = _reply.readString();
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
}
static final int TRANSACTION_getTrackFromParams = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
}
//param params--> Map of params:
//  "pathToFileResult"-->String with the path to where the result must be saved, including file name and extension
//                    -->if null, the track is passed via the return argument
//  "maxRunningTime"-->String with a number of seconds for the routing timeout, default = 60
//  "trackFormat"-->[kml|gpx] default = gpx
//  "lats"-->double[] array of latitudes; 2 values at least.
//  "lons"-->double[] array of longitudes; 2 values at least.
//  "nogoLats"-->double[] array of nogo latitudes; may be null.
//  "nogoLons"-->double[] array of nogo longitudes; may be null.
//  "nogoRadi"-->double[] array of nogo radius in meters; may be null.
//  "fast"-->[0|1]
//  "v"-->[motorcar|bicycle|foot]
//return null if all ok and no path given, the track if ok and path given, an error message if it was wrong
//call in a background thread, heavy task!

public java.lang.String getTrackFromParams(android.os.Bundle params) throws android.os.RemoteException;
}
