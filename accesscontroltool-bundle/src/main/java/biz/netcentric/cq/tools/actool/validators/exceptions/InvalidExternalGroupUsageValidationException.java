/*
 * (C) Copyright 2015 Netcentric AG.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.cq.tools.actool.validators.exceptions;

public class InvalidExternalGroupUsageValidationException extends AcConfigBeanValidationException {
    public InvalidExternalGroupUsageValidationException(String message) {
        super(message);
    }
    
    public InvalidExternalGroupUsageValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
