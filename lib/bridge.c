#include <caml/mlvalues.h>
#include <caml/memory.h>
#include <caml/alloc.h>
#include <caml/threads.h>
#ifdef __ANDROID__
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <caml/startup.h>
#include <caml/callback.h>
#include <caml/printexc.h>
#include <stdio.h>
static JavaVM *vm;
static jclass bridge_class;
static jmethodID dispatch_method, event_method;
static pthread_mutex_t startup_lock = PTHREAD_MUTEX_INITIALIZER;
static int started;
static void *run_ocaml(void *unused) {
  (void)unused;
  char *argv[] = {"android-ocaml", NULL};
  caml_startup(argv);
  const value *entry = caml_named_value("android.runtime.start");
  if (entry) {
    value result = caml_callback_exn(*entry, Val_unit);
    if (Is_exception_result(result)) {
      char *message = caml_format_exception(Extract_exception(result));
      fprintf(stderr, "Android OCaml runtime: %s\n", message);
      caml_stat_free(message);
    }
  }
  caml_release_runtime_system();
  return NULL;
}
JNIEXPORT void JNICALL Java_org_ocaml_android_Bridge_nativeStart(JNIEnv *env, jclass cls) {
  pthread_mutex_lock(&startup_lock);
  if (!started) {
    (*env)->GetJavaVM(env, &vm);
    bridge_class = (*env)->NewGlobalRef(env, cls);
    dispatch_method = (*env)->GetStaticMethodID(env, cls, "dispatch", "([B[[B)[[B");
    event_method = (*env)->GetStaticMethodID(env, cls, "takeEvent", "()[[B");
    if (bridge_class && dispatch_method && event_method && !(*env)->ExceptionCheck(env)) {
      pthread_t thread;
      if (pthread_create(&thread, NULL, run_ocaml, NULL) == 0) {
        started = 1; pthread_detach(thread);
      } else {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Cannot start OCaml thread");
      }
    }
  }
  pthread_mutex_unlock(&startup_lock);
}
static jbyteArray bytes(JNIEnv *env, value v) {
  jsize n = (jsize)caml_string_length(v);
  jbyteArray b = (*env)->NewByteArray(env, n);
  if (b) (*env)->SetByteArrayRegion(env, b, 0, n, (const jbyte *)String_val(v));
  return b;
}
static value exchange(value op, value args, int event) {
  CAMLparam2(op, args);
  CAMLlocal3(result, str, error);
  JNIEnv *env = NULL;
  int attached = 0, failed = 0;
  jobjectArray response = NULL;
  if (!vm) goto unavailable;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    if ((*vm)->AttachCurrentThread(vm, (void *)&env, NULL) != JNI_OK) goto unavailable;
    attached = 1;
  }
  if ((*env)->PushLocalFrame(env, 32) < 0) { failed = 1; goto finish; }
  jobjectArray arguments = NULL;
  jbyteArray command = NULL;
  if (!event) {
    jclass byte_array_class = (*env)->FindClass(env, "[B");
    arguments = (*env)->NewObjectArray(env, Wosize_val(args), byte_array_class, NULL);
    if (!arguments) { failed = 1; goto pop; }
    for (mlsize_t i = 0; i < Wosize_val(args); i++) {
      jbyteArray b = bytes(env, Field(args, i));
      if (!b) { failed = 1; goto pop; }
      (*env)->SetObjectArrayElement(env, arguments, i, b);
      (*env)->DeleteLocalRef(env, b);
    }
    command = bytes(env, op);
    if (!command) { failed = 1; goto pop; }
  }
  caml_release_runtime_system();
  response = event ? (*env)->CallStaticObjectMethod(env, bridge_class, event_method)
    : (*env)->CallStaticObjectMethod(env, bridge_class, dispatch_method, command, arguments);
  caml_acquire_runtime_system();
  if ((*env)->ExceptionCheck(env) || !response) { failed = 1; goto pop; }
  jsize count = (*env)->GetArrayLength(env, response);
  result = caml_alloc(count, 0);
  for (jsize i = 0; i < count; i++) Store_field(result, i, Val_unit);
  for (jsize i = 0; i < count; i++) {
    jbyteArray b = (*env)->GetObjectArrayElement(env, response, i);
    if (!b) { failed = 1; goto pop; }
    jsize n = (*env)->GetArrayLength(env, b);
    str = caml_alloc_string(n);
    (*env)->GetByteArrayRegion(env, b, 0, n, (jbyte *)Bytes_val(str));
    Store_field(result, i, str);
    (*env)->DeleteLocalRef(env, b);
  }
pop:
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); failed = 1; }
  (*env)->PopLocalFrame(env, NULL);
finish:
  if (attached) (*vm)->DetachCurrentThread(vm);
  if (!failed) CAMLreturn(result);
unavailable:
  result = caml_alloc(3, 0);
  for (int i=0; i<3; i++) Store_field(result, i, Val_unit);
  error = caml_copy_string("error"); Store_field(result, 0, error);
  error = caml_copy_string("unavailable"); Store_field(result, 1, error);
  error = caml_copy_string("Android bridge is not initialized"); Store_field(result, 2, error);
  CAMLreturn(result);
}
CAMLprim value android_invoke(value op, value args) { return exchange(op, args, 0); }
CAMLprim value android_take_event(value unit) { (void)unit; return exchange(Val_unit, Val_unit, 1); }
#else
CAMLprim value android_invoke(value op, value args) {
  CAMLparam2(op, args); CAMLlocal2(a, x);
  a = caml_alloc(3, 0);
  for (int i=0; i<3; i++) Store_field(a, i, Val_unit);
  x = caml_copy_string("error"); Store_field(a, 0, x);
  x = caml_copy_string("unavailable"); Store_field(a, 1, x);
  x = caml_copy_string("Android is unavailable on the host"); Store_field(a, 2, x);
  CAMLreturn(a);
}
CAMLprim value android_take_event(value unit) { return android_invoke(unit, unit); }
#endif
