package com.jazzautomation;

/** Class to hold version information. */
public class Version
{
  // don't modify, build script will do this.  Do not place comments after the end of the line.
  // This MUST be in the form of Major.Minor.Point - else build will fail
  public static final String VERSION = "0.9.112";

  private Version() {}

  public static String getVersion()
  {
    return VERSION;
  }
}
