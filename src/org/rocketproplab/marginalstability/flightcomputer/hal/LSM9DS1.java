package org.rocketproplab.marginalstability.flightcomputer.hal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

import org.rocketproplab.marginalstability.flightcomputer.math.Vector3;

import com.pi4j.io.i2c.I2CDevice;

/**
 * The HAL implementation for the LSM9DS1, datasheet can be found here: <a
 * href=https://www.st.com/resource/en/datasheet/lsm9ds1.pdf>https://www.st.com/resource/en/datasheet/lsm9ds1.pdf</a><br>
 * 
 * Every time poll is called this sensor will acquire as many samples as
 * possible from the LSM9DS1's internal FIFO buffer. This is done by reading the
 * number of samples in the FIFO and then reading the FIFO output registers
 * repeatedly. <br>
 * Each sample read gets put into a queue which can be accessed with the
 * {@link #getNext()} method. Whether or not the queue is empty can be read
 * using the {@link #hasNext()} method. It is recommended to call
 * {@link #hasNext()} before each call of {@link #getNext()}.
 * 
 * @author Max Apodaca
 *
 */
public class LSM9DS1 implements PollingSensor, IMU {
  private static final int ODR_MASK                    = 0b111;
  private static final int ODR_LSB_POS                 = 5;
  private static final int ACCELEROMETER_SCALE_MASK    = 0b11;
  private static final int ACCELEROMETER_SCALE_LSB_POS = 3;
  private static final int GYRO_SCALE_MASK             = 0b11;
  private static final int GYRO_SCALE_LSB_POS          = 3;
  private static final int FIFO_EN_VAL_MASK            = 0b1;
  private static final int FIFO_EN_LSB_POS             = 1;
  private static final int FIFO_MODE_MASK              = 0b111;
  private static final int FIFO_MODE_LSB_POS           = 5;
  private static final int FIFO_THRESHOLD_MASK         = 0b11111;
  private static final int FIFO_THRESHOLD_LSB_POS      = 0;
  public static final int  FIFO_THRESHOLD_MAX          = 31;
  public static final int  FIFO_THRESHOLD_MIN          = 0;
  public static final int  FIFO_OVERRUN_POS            = 6;
  public static final int  FIFO_THRESHOLD_STATUS_POS   = 7;
  public static final int  FIFO_SAMPLES_STORED_MASK    = 0b111111;

  private static final int BYTES_PER_FIFO_LINE = 12;
  private static final int BITS_PER_BYTE       = 8;

  /**
   * All the registers that can be found in the LSM9DS1, this is taken directly
   * from the datasheet.
   */
  public enum Registers {
    ACT_THS(0x04),
    ACT_DUR(0x05),
    INT_GEN_CFG_XL(0x06),
    INT_GEN_THS_X_XL(0x07),
    INT_GEN_THS_Y_XL(0x08),
    INT_GEN_THS_Z_XL(0x09),
    INT_GEN_DUR_XL(0x0A),
    REFERENCE_G(0x0B),
    INT1_CTRL(0x0C),
    INT2_CTRL(0x0D),
    WHO_AM_I(0x0F),
    CTRL_REG1_G(0x10),
    CTRL_REG2_G(0x11),
    CTRL_REG3_G(0x12),
    ORIENT_CFG_G(0x13),
    INT_GEN_SRC_G(0x14),
    OUT_TEMP_L(0x15),
    OUT_TEMP_H(0x16),
    STATUS_REG(0x17),
    OUT_X_L_G(0x18),
    OUT_X_H_G(0x19),
    OUT_Y_L_G(0x1A),
    OUT_Y_H_G(0x1B),
    OUT_Z_L_G(0x1C),
    OUT_Z_H_G(0x1D),
    CTRL_REG4(0x1E),
    CTRL_REG5_XL(0x1F),
    CTRL_REG6_XL(0x20),
    CTRL_REG7_XL(0x21),
    CTRL_REG8(0x22),
    CTRL_REG9(0x23),
    CTRL_REG10(0x24),
    INT_GEN_SRC_XL(0x26),
    STATUS_REG_2(0x27),
    OUT_X_L_XL(0x28),
    OUT_X_H_XL(0x29),
    OUT_Y_L_XL(0x2A),
    OUT_Y_H_XL(0x2B),
    OUT_Z_L_XL(0x2C),
    OUT_Z_H_XL(0x2D),
    FIFO_CTRL(0x2E),
    FIFO_SRC(0x2F),
    INT_GEN_CFG_G(0x30),
    INT_GEN_THS_XH_G(0x31),
    INT_GEN_THS_XL_G(0x32),
    INT_GEN_THS_YH_G(0x33),
    INT_GEN_THS_YL_G(0x34),
    INT_GEN_THS_ZH_G(0x35),
    INT_GEN_THS_ZL_G(0x36),
    INT_GEN_DUR_G(0x37);

    final int address;

    Registers(int address) {
      this.address = address;
    }

    /**
     * Read the address of a register, this is not necessarily the same as the
     * ordinal and should be used for I2C access.
     * 
     * @return the I2C address of the register.
     */
    public int getAddress() {
      return this.address;
    }
  }

  /**
   * An interface to make accessing each value of a register easier. The
   * {@link #getValueMask()} returns a bitmask of what section of the register
   * should be read and the {@link #getValueLSBPos()} method returns how many bits
   * to the left of the LSB the LSB of the value is.
   * 
   * @author Max Apodaca
   *
   */
  public interface RegisterValue {

    /**
     * Get a mask for the register in which this value is in. The mask will only
     * cover the specified value. <br>
     * For instance a register with three values aabbbccc would mean that value a
     * has a mask of 0b11000000;
     * 
     * @return the mask for this value for its register
     */
    public int getValueMask();

    /**
     * Get how many bits to the left of the register's LSB the LSB of the value is.
     * <br>
     * For instance if we have a register with three values aabbbccc the LSBPos for
     * value b would be 3 as the LSB of b is three bits to the left of the LSB of
     * the register as a whole. The LSBPos of c would be 0 and the LSBPos of a would
     * be 6.
     * 
     * @return how many bits to the left of the register's LSB the LSB of the value
     *         is
     */
    public int getValueLSBPos();

    /**
     * The value associated with the given register value. Setting the appropriate
     * bits in the value's register to this value will result in application of the
     * value. <br>
     * If this instance corresponds to a value of 01 for a in the register aabbbccc
     * then ordinal would return 0b01.<br>
     * <br>
     * <b>NOTE</b>: the implementation relies on enums which means the enum values
     * must be ordered correctly to yield a correct return value for ordinal. If the
     * enum has 2 members A_0 and A_1 and A_0 should have value 0 then A_0 must be
     * the first element in the enum.
     * 
     * @return the value of this value
     */
    public int ordinal();
  }

  public enum ODR implements RegisterValue {
    ODR_OFF,
    ODR_14_9,
    ODR_59_5,
    ODR_119,
    ODR_238,
    ODR_476,
    ODR_952;

    @Override
    public int getValueMask() {
      return ODR_MASK;
    }

    @Override
    public int getValueLSBPos() {
      return ODR_LSB_POS;
    }

  }

  public enum AccelerometerScale implements RegisterValue {
    G_2,
    G_16,
    G_4,
    G_8;

    @Override
    public int getValueMask() {
      return ACCELEROMETER_SCALE_MASK;
    }

    @Override
    public int getValueLSBPos() {
      return ACCELEROMETER_SCALE_LSB_POS;
    }
  }

  public enum GyroScale implements RegisterValue {
    DPS_245,
    DPS_500,
    /**
     * DPS_NA is a filler see note in {@link RegisterValue#ordinal()} for more
     * information.
     */
    DPS_NA,
    DPS_2000;

    @Override
    public int getValueMask() {
      return GYRO_SCALE_MASK;
    }

    @Override
    public int getValueLSBPos() {
      return GYRO_SCALE_LSB_POS;
    }
  }

  public enum FIFOMode implements RegisterValue {
    BYPASS,
    FIFO,
    /**
     * NA is a filler see note in {@link RegisterValue#ordinal()} for more
     * information.
     */
    NA,
    CONTINUOUS_THEN_FIFO,
    BYPASS_THEN_CONTINUOUS,
    /**
     * NA_2 is a filler see note in {@link RegisterValue#ordinal()} for more
     * information.
     */
    NA_2,
    CONTINUOUS;

    @Override
    public int getValueMask() {
      return FIFO_MODE_MASK;
    }

    @Override
    public int getValueLSBPos() {
      return FIFO_MODE_LSB_POS;
    }
  }

  private I2CDevice              i2c;
  private ArrayDeque<IMUReading> samples = new ArrayDeque<>();

  /**
   * Create a new LSM9DS1 on the given {@link I2CDevice}. There is no validation
   * for the {@link I2CDevice} address.
   * 
   * @param device the device to use for I2C communication
   */
  public LSM9DS1(I2CDevice device) {
    this.i2c = device;
  }

  /**
   * Sets the output data rate of the sensor
   * 
   * @throws IOException if unable to read
   */
  public void setODR(ODR odr) throws IOException {
    genericRegisterWrite(Registers.CTRL_REG1_G, odr);
  }

  /**
   * Sets the scale of the accelerometer
   * 
   * @param scale the scale of the accelerometer data
   * @throws IOException if we are unable to access the i2c device
   */
  public void setAccelerometerScale(AccelerometerScale scale) throws IOException {
    genericRegisterWrite(Registers.CTRL_REG6_XL, scale);
  }

  /**
   * Sets the scale of the Gyroscope
   * 
   * @param scale the scale of the gyroscope data
   * @throws IOException if we are unable to access the i2c device
   */
  public void setGyroscopeScale(GyroScale scale) throws IOException {
    genericRegisterWrite(Registers.CTRL_REG1_G, scale);
  }

  /**
   * Enable or disable the FIFO by setting CTRL_REG9
   * 
   * @param enabled whether or not to enable the fifo
   * @throws IOException if we are unable to access the i2c device
   */
  public void setFIFOEnabled(boolean enabled) throws IOException {
    int registerValue = this.i2c.read(Registers.CTRL_REG9.getAddress());
    int result        = mask(registerValue, enabled ? 1 : 0, FIFO_EN_LSB_POS, FIFO_EN_VAL_MASK);
    this.i2c.write(Registers.CTRL_REG9.getAddress(), (byte) result);
  }

  /**
   * Set the FIFOMode to the new mode
   * 
   * @param mode the new FIFOMode to use
   * @throws IOException if we are unable to access the i2c device
   */
  public void setFIFOMode(FIFOMode mode) throws IOException {
    genericRegisterWrite(Registers.FIFO_CTRL, mode);
  }

  /**
   * Set the threshold at which we should signal that the FIFO is full.
   * 
   * @param threshold the number of samples at which we start signaling
   * @throws IOException if we are unable to access the i2c device
   */
  public void setFIFOThreshold(int threshold) throws IOException {
    if (threshold > FIFO_THRESHOLD_MAX) {
      threshold = FIFO_THRESHOLD_MAX;
    }
    if (threshold < FIFO_THRESHOLD_MIN) {
      threshold = FIFO_THRESHOLD_MIN;
    }
    int registerValue = this.i2c.read(Registers.FIFO_CTRL.getAddress());
    int result        = mask(registerValue, threshold, FIFO_THRESHOLD_LSB_POS, FIFO_THRESHOLD_MASK);
    this.i2c.write(Registers.FIFO_CTRL.getAddress(), (byte) result);
  }

  /**
   * Returns if the FIFO buffer's data has been exceeded. This means that data was
   * lost.
   * 
   * @return if the FIFO overflowed
   * @throws IOException if we are unable to access the i2c device
   */
  public boolean hasFIFOOverrun() throws IOException {
    int fifoSRCValue = this.i2c.read(Registers.FIFO_SRC.getAddress());
    int masked       = (1 << FIFO_OVERRUN_POS) & fifoSRCValue;
    return masked != 0;
  }

  /**
   * Are we at the FIFO threshold yet. See {@link #setFIFOThreshold(int)} on how
   * to set the threshold.
   * 
   * @return if we are at the FIFO threshold
   * @throws IOException if we are unable to access the i2c device
   */
  public boolean isFIFOThresholdReached() throws IOException {
    int fifoSRCValue = this.i2c.read(Registers.FIFO_SRC.getAddress());
    int masked       = (1 << FIFO_THRESHOLD_STATUS_POS) & fifoSRCValue;
    return masked != 0;
  }

  /**
   * Read how many samples are in the FIFO at the current time.
   * 
   * @return the number of samples in the FIFO
   * @throws IOException if we are unable to access the i2c device
   */
  public int getSamplesInFIFO() throws IOException {
    int fifoSRCValue = this.i2c.read(Registers.FIFO_SRC.getAddress());
    int masked       = FIFO_SAMPLES_STORED_MASK & fifoSRCValue;
    return masked;
  }

  /**
   * Write a register value to a register.
   * 
   * @param register the register to write to
   * @param value    the value to write, uses all of the values in
   *                 {@link RegisterValue}
   * @throws IOException if we are unable to access the i2c device
   */
  private void genericRegisterWrite(Registers register, RegisterValue value) throws IOException {
    int registerValue = this.i2c.read(register.getAddress());
    int result        = mask(registerValue, value.ordinal(), value.getValueLSBPos(), value.getValueMask());
    this.i2c.write(register.getAddress(), (byte) result);
  }

  /**
   * Masks the value in toMask with the given parameters. newData is the data to
   * replace the masked bits. LSBPos is how many bits to the left newData is in
   * toMask. valueMask is the mask for the value in newData. valueMask should be
   * right aligned. valueMask will be shifted lsbPos bits to the left before
   * anding with toMask.<br>
   * <br>
   * <b>Note</b>: No values in newData are masked out.
   * 
   * @param toMask    the value to mask
   * @param newData   the value to replace the masked areas of to mask
   * @param lsbPos    how far to the left the masked value is in toMask
   * @param valueMask the mask to apply to toMask but right aligned.
   * @return combination of toMask and newData combined based on valueMask
   */
  private int mask(int toMask, int newData, int lsbPos, int valueMask) {
    int mask    = valueMask << lsbPos;
    int notMask = ~mask;
    int result  = newData << lsbPos | (toMask & notMask);
    return result;
  }

  @Override
  public void poll() {
    this.readFromSensor();
  }

  /**
   * Read as many samples as possible from the sensor. This method will read the
   * number of samples in the FIFO by calling {@link #getSamplesInFIFO()} and then
   * read that many samples. The read is done by reading register OUT_X_L_G which
   * will automatically advance until the sample is read. This means we will read
   * BYTES_PER_FIFO_LINE (12) * N bytes where N is the number of samples in the
   * FIFO. <br>
   * <br>
   * If this method is ever extended make sure to read less than 8000 bytes at a
   * time as that is the limit of the I2C driver. We will read at most 372 bytes
   * as the maximum FIFO size is 31 samples.
   */
  private void readFromSensor() {
    try {
      int samplesInFIFO = this.getSamplesInFIFO();
      if (samplesInFIFO == 0) {
        return;
      }
      int    dataLength  = samplesInFIFO * BYTES_PER_FIFO_LINE;
      byte[] data        = new byte[dataLength];
      int    samplesRead = this.i2c.read(data, Registers.OUT_X_L_G.getAddress(), dataLength);
      this.parseReadings(data, samplesRead);
    } catch (IOException e) {
      // TODO Report IO Error
      e.printStackTrace();
    }
  }

  /**
   * Take the raw data read and split it into chunks of BYTES_PER_FIFO_LINE bytes
   * to correspond to each IMU reading. Then feed those into
   * {@link #buildReading(byte[])}.
   * 
   * @param data      the raw data to parse
   * @param bytesRead how many bytes were read.
   */
  private void parseReadings(byte[] data, int bytesRead) {
    int samplesRead = bytesRead / BYTES_PER_FIFO_LINE;
    for (int i = 0; i < samplesRead; i++) {
      int        start   = i * BYTES_PER_FIFO_LINE;
      int        end     = (i + 1) * BYTES_PER_FIFO_LINE;
      byte[]     samples = Arrays.copyOfRange(data, start, end);
      IMUReading reading = this.buildReading(samples);
      this.samples.add(reading);
    }
  }

  /**
   * Parse a set of BYTES_PER_FIFO_LINE bytes into an IMUReading. <br>
   * TODO Use range to normalize to m/s^2
   * 
   * @param data the set of six bites representing a reading
   * @return the IMUReading which the six bytes belong to.
   */
  public IMUReading buildReading(byte[] data) {
    int[] results = this.getData(data);

    int xGyro = results[0];
    int yGyro = results[1];
    int zGyro = results[2];
    int xAcc  = results[3];
    int yAcc  = results[4];
    int zAcc  = results[5];

    Vector3 gyroVec = new Vector3(xGyro, yGyro, zGyro);
    Vector3 accVec  = new Vector3(xAcc, yAcc, zAcc);
    return new IMUReading(accVec, gyroVec);
  }

  /**
   * Converts the input bytes to shorts where it assumes that the first byte of
   * the tuple is less significant. <br>
   * The array looks like {@code [L,H,L,H, ..., L, H]}.
   * 
   * @param data byte array of little-endian shorts
   * @return array of the shorts
   */
  private int[] getData(byte[] data) {
    int[] results = new int[data.length / 2];
    for (int i = 0; i < data.length / 2; i++) {
      short low    = (short) (char) data[i * 2];
      short high   = (short) (char) data[i * 2 + 1];
      short result = (short) (low | (high << BITS_PER_BYTE));
      results[i] = result;
    }
    return results;
  }

  @Override
  public IMUReading getNext() {
    return samples.pollFirst();
  }

  @Override
  public boolean hasNext() {
    return !samples.isEmpty();
  }

}
