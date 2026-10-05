package android.content;
/** The public methods used by the production selection contract. */
public interface SharedPreferences {
    String getString(String key,String fallback);
    boolean contains(String key);
    Editor edit();
    interface Editor {
        Editor putString(String key,String value);
        Editor remove(String key);
        boolean commit();
    }
}
