package com.jazzautomation.page;

import java.util.List;

public class DomElementExpect
{
  private String         componentName;
  private String         condition;  // must be a format of WebActionConditionEnum
  private String         value;
  boolean        negative = false;
  private String message;
  private boolean        customAction;
  private Class customActionClass;

  public String getComponentName()
  {
    return componentName;
  }

  public void setComponentName(String componentName)
  {
    this.componentName = componentName;
  }

  public String getMessage()
  {
    return message;
  }

  public void setMessage(String message)
  {
    this.message = message;
  }

  public String getCondition()
  {
    return condition;
  }

  public void setCondition(String condition)
  {
    this.condition = condition;
  }

  public String getValue()
  {
    return value;
  }

  public void setValue(String value)
  {
    this.value = value;
  }

  public Class getCustomActionClass()
  {
    return customActionClass;
  }

  public void setCustomActionClass(Class customActionClass)
  {
    this.customActionClass = customActionClass;
  }

  public boolean isNegative()
  {
    return negative;
  }

  public void setNegative(boolean negative)
  {
    this.negative = negative;
  }

  public String toString()
  {
    StringBuffer returnStringBuffer = new StringBuffer();

    returnStringBuffer.append(componentName);

    if (condition.equalsIgnoreCase(HtmlActionConditionEnum.EXISTS.getValue()) || condition.equalsIgnoreCase(HtmlActionConditionEnum.VISIBLE.getValue()))
    {
      returnStringBuffer.append("." + HtmlActionConditionEnum.EXISTS.getValue());
    }
    else
    {
      returnStringBuffer.append(condition);
      returnStringBuffer.append(value);
    }

    return returnStringBuffer.toString();
  }

  public boolean isCustomAction()
  {
    return customAction;
  }

  public void setCustomAction(boolean customAction)
  {
    this.customAction = customAction;
  }

  public static String normalizeExpects(List<DomElementExpect> someExpects)
  {
    if (someExpects == null)
    {
      return "";
    }

    String returnString = "";

    for (int i = 0; i < someExpects.size(); i++)
    {
      if (i == 0)
      {
        returnString += "[";
      }

      returnString += someExpects.get(i).toString();

      if (i != (someExpects.size() - 1))
      {
        returnString += ",";
      }
      else
      {
        returnString += "]";
      }
    }

    return returnString;
  }
}
