package android.os;

/** Only for InputOptions host parsing tests; no JSON or graphics behavior is stubbed. */
public class Bundle {
    private final java.util.Map<String,Object> values=new java.util.HashMap<>();
    public Object get(String key){return values.get(key);}
    public void putBoolean(String key,boolean value){values.put(key,value);}
    public void putString(String key,String value){values.put(key,value);}
}
