package com.ruoyi.agent.core;

/**
 * 工具的副作用等级。决定要不要过第二道闸（确认）。
 *
 * @author ruoyi
 */
public enum SideEffect
{
    /** 只读 */
    READ,
    /** 写库，可撤销 */
    WRITE,
    /** 写设备，二期 */
    DEVICE
}
