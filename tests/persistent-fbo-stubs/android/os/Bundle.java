package android.os;

/** Only for InputOptions host parsing tests; no JSON or graphics behavior is stubbed. */
public class Bundle {
    private final java.util.Map<String,Object> values=new java.util.HashMap<>();
    public Object get(String key){return values.get(key);}
    public boolean containsKey(String key){return values.containsKey(key);}
    public void putBoolean(String key,boolean value){values.put(key,value);}
    public void putString(String key,String value){values.put(key,value);}
    public void putInt(String key,int value){values.put(key,value);}
    public void putLong(String key,long value){values.put(key,value);}
    public void putFloat(String key,float value){values.put(key,value);}
}
