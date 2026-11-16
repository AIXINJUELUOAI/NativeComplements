package dev.local.sunwindowdemo;

import java.lang.reflect.*;
import java.util.*;

/** Only the callback-discovery ABI differs; admission and corner rules stay in BackBridge. */
final class EdgeBackHookTarget {
    record Callback(Method invoke,Field owner) {}
    record ConstructorCallback(Constructor<?> constructor,int argument) {}
    static Callback synthetic(Class<?> display,Class<?> handler,ClassLoader loader)throws Exception{
        Class<?> callback=field(display,"onInputEvent").getType();
        if(callback.isInterface())callback=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler$$ExternalSyntheticLambda20",false,loader);
        Field owner=field(callback,"f$0");
        if(owner.getType()!=handler||Modifier.isStatic(owner.getModifiers()))throw new NoSuchFieldException("edge owner");
        return new Callback(invoke(callback),owner);
    }
    static List<ConstructorCallback> constructors(Class<?> display)throws Exception{
        Class<?> callback=field(display,"onInputEvent").getType();
        if(!callback.isInterface()||!"kotlin.jvm.functions.Function1".equals(callback.getName()))
            throw new NoSuchFieldException("edge Function1 callback");
        List<ConstructorCallback> result=new ArrayList<>();
        for(Constructor<?> constructor:display.getDeclaredConstructors()){
            Class<?>[] types=constructor.getParameterTypes();int index=-1;
            for(int i=0;i<types.length;i++)if(types[i]==callback){
                if(index>=0)throw new NoSuchMethodException("ambiguous edge callback argument");index=i;
            }
            if(index>=0){constructor.setAccessible(true);result.add(new ConstructorCallback(constructor,index));}
        }
        if(result.isEmpty())throw new NoSuchMethodException("edge callback constructor");
        return result;
    }
    static Callback captured(Class<?> callback,Class<?> handler)throws Exception{
        Field owner=null;
        for(Field candidate:callback.getDeclaredFields())if(candidate.getType()==handler&&!Modifier.isStatic(candidate.getModifiers())){
            if(owner!=null)throw new NoSuchFieldException("ambiguous captured edge owner");owner=candidate;
        }
        if(owner==null)throw new NoSuchFieldException("captured EdgeBackGestureHandler");
        owner.setAccessible(true);return new Callback(invoke(callback),owner);
    }
    private static Method invoke(Class<?> callback)throws Exception{
        Method invoke=callback.getDeclaredMethod("invoke",Object.class);
        if(invoke.getReturnType()!=Object.class||Modifier.isStatic(invoke.getModifiers()))throw new NoSuchMethodException("edge callback invoke");
        invoke.setAccessible(true);return invoke;
    }
    private static Field field(Class<?> owner,String name)throws Exception{
        Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;
    }
}
