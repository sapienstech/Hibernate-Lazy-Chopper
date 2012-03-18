package com.tikal.lazychopper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.core.Ordered;

public interface LazyInitializationChopperAdvice extends Ordered {

	Object chop(ProceedingJoinPoint call) throws Throwable;

	@Override
	public abstract int getOrder();

}