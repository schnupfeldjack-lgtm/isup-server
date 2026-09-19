package com.hiki.isup.sdk;

import com.sun.jna.Structure;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 海康 SDK 结构体基类。
 *
 * <p>作用：自动按声明顺序生成 JNA 需要的字段顺序（getFieldOrder），
 * 避免在每一个 NET_EHOME_* 结构体里手写几十个字段名。</p>
 *
 * <p>注意：必须与参考实现保持一致——只收集 public 非 static 字段。</p>
 */
public abstract class HikSdkStructure extends Structure {

    @Override
    protected List<String> getFieldOrder() {
        List<String> fieldOrderList = new ArrayList<>();
        for (Class<?> cls = getClass();
             cls != null && !cls.equals(HikSdkStructure.class);
             cls = cls.getSuperclass()) {
            Field[] fields = cls.getDeclaredFields();
            for (Field field : fields) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers)) {
                    continue;
                }
                fieldOrderList.add(field.getName());
            }
        }
        return fieldOrderList;
    }
}
