package droidkit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Daniel Serdyukov
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface OnLoadFinished {

    int[] value();

}
