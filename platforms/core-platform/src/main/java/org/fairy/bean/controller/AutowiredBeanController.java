package org.fairy.bean.controller;

import org.apache.logging.log4j.LogManager;
import org.fairy.bean.Autowired;
import org.fairy.bean.BeanContext;
import org.fairy.bean.BeanHolder;
import org.fairy.bean.details.BeanDetails;
import org.fairy.reflect.Reflect;
import org.fairy.util.AccessUtil;
import org.fairy.util.Utility;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Optional;

public class AutowiredBeanController implements BeanController {

    public static AutowiredBeanController INSTANCE;

    public AutowiredBeanController() {
        INSTANCE = this;
    }

    @Override
    public void applyBean(BeanDetails beanDetails) throws Exception {
        Object object = beanDetails.getInstance();
        if (object != null) {
            this.applyObject(object);
        }
    }

    @Override
    public void removeBean(BeanDetails beanDetails) {

    }

    public void applyObject(Object instance) throws ReflectiveOperationException {
        Collection<Field> fields = Reflect.getDeclaredFields(instance.getClass());

        for (Field field : fields) {
            int modifiers = field.getModifiers();
            Autowired annotation = field.getAnnotation(Autowired.class);

            if (annotation == null || Modifier.isStatic(modifiers)) {
                continue;
            }

            if (Modifier.isFinal(modifiers)) {
                throw new IllegalStateException("The field " + field + " is final but marked @Autowired");
            }

            this.applyField(field, instance);
        }
    }

    public void applyField(Field field, Object instance) throws ReflectiveOperationException {
        Class<?> type = field.getType();
        boolean optional = false, beanHolder = false;
        if (type == Optional.class) {
            optional = true;
            type = Utility.sneaky(() -> Reflect.getParameter(field, 0));
            if (type == null) {
                return;
            }
        } else if (type == BeanHolder.class) {
            beanHolder = true;

            type = Utility.sneaky(() -> Reflect.getParameter(field, 0));
            if (type == null) {
                return;
            }
        }
        Object objectToInject = BeanContext.INSTANCE.getBean(type);
        if (optional) {
            objectToInject = Optional.ofNullable(objectToInject);
        } else if (beanHolder) {
            objectToInject = new BeanHolder<>(objectToInject);
        }

        if (objectToInject != null) {
            AccessUtil.setAccessible(field);
            Reflect.setField(instance, field, objectToInject);
        } else {
            LogManager.getLogger().error("The Autowired field " + field + " trying to wired with type " + type.getSimpleName() + " but couldn't find any matching Service! (or not being registered)");
        }
    }
}
