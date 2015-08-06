package droidkit.processor;

import javax.lang.model.element.TypeElement;

/**
 * @author Daniel Serdyukov
 */
public abstract class ElementScanner {

    protected static final String AUTO_GENERATED_FILE = "AUTO-GENERATED FILE. DO NOT MODIFY.";

    private final ProcessingEnv mEnv;

    private final TypeElement mOriginType;

    protected ElementScanner(ProcessingEnv env, TypeElement originType) {
        mEnv = env;
        mOriginType = originType;
    }

    public ProcessingEnv getEnv() {
        return mEnv;
    }

    protected TypeElement getOrigin() {
        return mOriginType;
    }

    protected abstract void scan();

}
