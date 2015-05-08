/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * User: anna
 * Date: 03-Jun-2009
 */
package com.intellij.junit4;

import com.intellij.rt.execution.junit.ComparisonFailureData;
import jetbrains.buildServer.messages.serviceMessages.MapSerializerUtil;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes;
import junit.framework.ComparisonFailure;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.PrintStream;
import java.util.*;

public class SMTestSender extends RunListener {
  private static final String MESSAGE_LENGTH_FOR_PATTERN_MATCHING = "idea.junit.message.length.threshold";
  private static final String JUNIT_FRAMEWORK_COMPARISON_NAME = ComparisonFailure.class.getName();
  private static final String ORG_JUNIT_COMPARISON_NAME = "org.junit.ComparisonFailure";
  public static final String EMPTY_SUITE_NAME = "junit.framework.TestSuite$1";
  public static final String EMPTY_SUITE_WARNING = "warning";

  private List myStartedSuites = new ArrayList();
  private Map   myParents = new HashMap();
  private final PrintStream myPrintStream;
  private String myRootName;

  public SMTestSender() {
    myPrintStream = System.out;
  }

  public SMTestSender(PrintStream printStream) {
    myPrintStream = printStream;
  }

  private static String escapeName(String str) {
    return MapSerializerUtil.escapeStr(str, MapSerializerUtil.STD_ESCAPER);
  }

  public void testRunStarted(Description description) throws Exception {
    myPrintStream.println("##teamcity[enteredTheMatrix]");
    if (myRootName != null && !myRootName.startsWith("[")) {
      int lastPointIdx = myRootName.lastIndexOf('.');
      String name = myRootName;
      String comment = null;
      if (lastPointIdx >= 0) {
        name = myRootName.substring(lastPointIdx + 1);
        comment = myRootName.substring(0, lastPointIdx);
      }

      myPrintStream.println("##teamcity[rootName name = \'" + escapeName(name) + 
                            (comment != null ? ("\' comment = \'" + escapeName(comment)) : "") + "\'" +
                            " location = \'java:suite://" + escapeName(myRootName) +
                            "\']");
      myRootName = getShortName(myRootName);
    }
  }

  public void testRunFinished(Result result) throws Exception {
    for (int i = myStartedSuites.size() - 1; i>= 0; i--) {
      Object parent = myStartedSuites.get(i);
      myPrintStream.println("##teamcity[testSuiteFinished name=\'" + escapeName((String)parent) + "\']");
    }
    myStartedSuites.clear();
  }

  public void testStarted(Description description) throws Exception {
    final String methodName = JUnit4ReflectionUtil.getMethodName(description);
    final String classFQN = JUnit4ReflectionUtil.getClassName(description);

    final List parents = (List)myParents.get(description);
    if (parents != null) {

      List parentsHierarchy = (List)parents.remove(0);
      
      int idx = 0;
      String currentClass;
      String currentParent;
      while (idx < myStartedSuites.size() && idx < parentsHierarchy.size()) {
        currentClass = (String)myStartedSuites.get(idx);
        currentParent = getShortName(JUnit4ReflectionUtil.getClassName((Description)parentsHierarchy.get(parentsHierarchy.size() - 1 - idx)));
        if (!currentClass.equals(currentParent)) break;
        idx++;
      }

      for (int i = myStartedSuites.size() - 1; i >= idx; i--) {
        currentClass = (String)myStartedSuites.remove(i);
        myPrintStream.println("##teamcity[testSuiteFinished name=\'" + escapeName(currentClass) + "\']");
      }

      for (int i = idx; i < parentsHierarchy.size(); i++) {
        Description parent = (Description)parentsHierarchy.get(parentsHierarchy.size() - 1 - i);
        final String className = getShortName(JUnit4ReflectionUtil.getClassName(parent));
        if (!className.equals(myRootName)) {
          myPrintStream.println("##teamcity[testSuiteStarted name=\'" + escapeName(className) + "\']");
          myStartedSuites.add(className);
        }
      }
    }

    myPrintStream.println("##teamcity[testStarted name=\'" + escapeName(methodName) + "\' " + 
                          getTestMethodLocation(methodName, classFQN) + "]");
  }

  public void testFinished(Description description) throws Exception {
    myPrintStream.println("\n##teamcity[testFinished name=\'" + escapeName(JUnit4ReflectionUtil.getMethodName(description)) + "\']");
  }

  public void testFailure(Failure failure) throws Exception {
    final String failureMessage = failure.getMessage();
    final String trace = failure.getTrace();
    final Map attrs = new HashMap();
    attrs.put("name", JUnit4ReflectionUtil.getMethodName(failure.getDescription()));
    final ComparisonFailureData notification = createExceptionNotification(failure.getException());
    ComparisonFailureData.registerSMAttributes(notification, trace, failureMessage, attrs);
    myPrintStream.println(ServiceMessage.asString(ServiceMessageTypes.TEST_FAILED, attrs));
  }

  public void testAssumptionFailure(Failure failure) {
    prepareIgnoreMessage(failure.getDescription(), false);
  }

  public synchronized void testIgnored(Description description) throws Exception {
    testStarted(description);
    prepareIgnoreMessage(description, true);
    testFinished(description);
  }

  private void prepareIgnoreMessage(Description description, boolean commentMessage) {
    Map attrs = new HashMap();
    if (commentMessage) {
      try {
        final Ignore ignoredAnnotation = (Ignore)description.getAnnotation(Ignore.class);
        if (ignoredAnnotation != null) {
          final String val = ignoredAnnotation.value();
          if (val != null) {
            attrs.put("message", val);
          }
        }
      }
      catch (NoSuchMethodError ignored) {
        //junit < 4.4
      }
    }
    attrs.put("name", JUnit4ReflectionUtil.getMethodName(description));
    myPrintStream.println(ServiceMessage.asString(ServiceMessageTypes.TEST_IGNORED, attrs));
  }

  private static boolean isComparisonFailure(Throwable throwable) {
    if (throwable == null) return false;
    return isComparisonFailure(throwable.getClass());
  }

  private static boolean isComparisonFailure(Class aClass) {
    if (aClass == null) return false;
    final String throwableClassName = aClass.getName();
    if (throwableClassName.equals(JUNIT_FRAMEWORK_COMPARISON_NAME) || throwableClassName.equals(ORG_JUNIT_COMPARISON_NAME)) return true;
    return isComparisonFailure(aClass.getSuperclass());
  }

  static ComparisonFailureData createExceptionNotification(Throwable assertion) {
    if (isComparisonFailure(assertion)) {
      return ComparisonFailureData.create(assertion);
    }
    try {
      final Throwable cause = assertion.getCause();
      if (isComparisonFailure(cause)) {
        return ComparisonFailureData.create(assertion);
      }
    }
    catch (Throwable ignore) {
    }
    final String message = assertion.getMessage();
    if (message != null  && acceptedByThreshold(message.length())) {
      try {
        return ExpectedPatterns.createExceptionNotification(message);
      }
      catch (Throwable ignored) {}
    }
    return null;
  }

  private static boolean acceptedByThreshold(int messageLength) {
    int threshold = 10000;
    try {
      final String property = System.getProperty(MESSAGE_LENGTH_FOR_PATTERN_MATCHING);
      if (property != null) {
        try {
          threshold = Integer.parseInt(property);
        }
        catch (NumberFormatException ignore) {}
      }
    }
    catch (SecurityException ignored) {}
    return messageLength < threshold;
  }

  private void sendTree(Description description, Description parent, List currentParents) {
    List pParents = new ArrayList(3);
    pParents.addAll(currentParents);
    if (parent != null && !myRootName.equals(JUnit4ReflectionUtil.getClassName(parent))) {
      pParents.add(0, parent);
    }

    String className = JUnit4ReflectionUtil.getClassName(description);
    if (description.getChildren().isEmpty()) {
      final String methodName = JUnit4ReflectionUtil.getMethodName((Description)description);
      if (methodName != null) {
        if (parent != null) {
          List parents = (List)myParents.get(description);
          if (parents == null) {
            parents = new ArrayList(1);
            myParents.put(description, parents);
          }
          parents.add(pParents);
        }
        if (isWarning(methodName, className)) {
          className = JUnit4ReflectionUtil.getClassName(parent);
        }
        myPrintStream.println("##teamcity[suiteTreeNode name=\'" + escapeName(methodName) + "\' " + getTestMethodLocation(methodName, className) + "]");
      }

      return;
    }
   
    List tests = description.getChildren();
    boolean pass = false;
    for (Iterator iterator = tests.iterator(); iterator.hasNext(); ) {
      final Object next = iterator.next();
      final Description nextDescription = (Description)next;
      if ((myRootName == null || !myRootName.equals(className)) && !pass) {
        pass = true;
        String locationHint = className;
        if (isParameter((Description)description)) {
          final String displayName = nextDescription.getDisplayName();
          final int paramIdx = displayName.indexOf(locationHint);
          if (paramIdx > -1) {
            locationHint = displayName.substring(paramIdx + locationHint.length());
            if (locationHint.startsWith("(") && locationHint.endsWith(")")) {
              locationHint = locationHint.substring(1, locationHint.length() - 1) + "." + className; 
            }
          }
        }
        myPrintStream.println("##teamcity[suiteTreeStarted name=\'" + escapeName(getShortName(className)) + "\' locationHint=\'java:suite://" + escapeName(locationHint) + "\']");
      }
      sendTree(nextDescription, description, pParents);
    }
    if (pass) {
      myPrintStream.println("##teamcity[suiteTreeEnded name=\'" + escapeName(getShortName(JUnit4ReflectionUtil.getClassName((Description)description))) + "\']");
    }
  }

  private static boolean isWarning(String methodName, String className) {
    return EMPTY_SUITE_WARNING.equals(methodName) && EMPTY_SUITE_NAME.equals(className);
  }

  private static String getTestMethodLocation(String methodName, String className) {
    return "locationHint=\'java:test://" + escapeName(className + "." + methodName) + "\'";
  }

  private static boolean isParameter(Description description) {
    String displayName = description.getDisplayName();
    return displayName.startsWith("[") && displayName.endsWith("]");
  }

  public void sendTree(Description description) {
    myRootName = JUnit4ReflectionUtil.getClassName((Description)description);
    sendTree(description, null, new ArrayList());
  }

  private static String getShortName(String fqName) {
    if (fqName == null) return null;
    if (fqName.startsWith("[")) {
      //param name
      return fqName;
    }
    int lastPointIdx = fqName.lastIndexOf('.');
    if (lastPointIdx >= 0) {
      return fqName.substring(lastPointIdx + 1);
    }
    return fqName;
  }
}