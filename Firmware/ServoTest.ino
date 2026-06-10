#include <Servo.h>

Servo servoA;
Servo servoB;

const int pinServoA = 9;
const int pinServoB = 10;

// Servo merkezleri
const int CENTER_A = 90;
const int CENTER_B = 90;

// Yön tersse true yap
const bool REVERSE_A = false;
const bool REVERSE_B = false;

// Kullanıcının verdiği mantıksal açılar
int inputA = 0;   // -90 ... +90
int inputB = 0;   // -90 ... +90

String inputBuffer = "";

int angleToServoRaw(int inputAngle, int centerAngle, bool reverseDir) {
  inputAngle = constrain(inputAngle, -90, 90);

  if (reverseDir) {
    inputAngle = -inputAngle;
  }

  int rawAngle = centerAngle + inputAngle;
  rawAngle = constrain(rawAngle, 0, 180);

  return rawAngle;
}

void moveServoA(int inputAngle) {
  inputA = constrain(inputAngle, -90, 90);
  int rawA = angleToServoRaw(inputA, CENTER_A, REVERSE_A);
  servoA.write(rawA);
}

void moveServoB(int inputAngle) {
  inputB = constrain(inputAngle, -90, 90);
  int rawB = angleToServoRaw(inputB, CENTER_B, REVERSE_B);
  servoB.write(rawB);
}

void setup() {
  Serial.begin(115200);

  servoA.attach(pinServoA);
  servoB.attach(pinServoB);

  // Başlangıçta ikisi de merkeze gelsin
  moveServoA(0);
  moveServoB(0);

  Serial.println("Sistem hazir.");
  Serial.println("Mantik: servo_raw = 90 + girilen_aci");
  Serial.println("Komutlar:");
  Serial.println("A0 B0");
  Serial.println("A20  -> servo A 110 derece");
  Serial.println("A-20 -> servo A 70 derece");
  Serial.println("B45  -> servo B 135 derece");
  Serial.println("B-45 -> servo B 45 derece");
  Serial.println("A30 B-20");
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();

    if (c == '\n' || c == '\r') {
      if (inputBuffer.length() > 0) {
        parseCommand(inputBuffer);
        inputBuffer = "";
      }
    } else {
      inputBuffer += c;
    }
  }
}

void parseCommand(String cmd) {
  cmd.trim();
  cmd.toUpperCase();

  int newA = inputA;
  int newB = inputB;

  bool aFound = false;
  bool bFound = false;

  int aIndex = cmd.indexOf('A');
  int bIndex = cmd.indexOf('B');

  if (aIndex != -1) {
    int start = aIndex + 1;
    int end = cmd.indexOf(' ', start);
    if (end == -1) end = cmd.length();

    String aStr = cmd.substring(start, end);
    newA = aStr.toInt();
    aFound = true;
  }

  if (bIndex != -1) {
    int start = bIndex + 1;
    int end = cmd.indexOf(' ', start);
    if (end == -1) end = cmd.length();

    String bStr = cmd.substring(start, end);
    newB = bStr.toInt();
    bFound = true;
  }

  // Format: "20 -30"
  // ilk sayı A, ikinci sayı B
  if (!aFound && !bFound) {
    int spaceIndex = cmd.indexOf(' ');
    if (spaceIndex != -1) {
      String aStr = cmd.substring(0, spaceIndex);
      String bStr = cmd.substring(spaceIndex + 1);

      aStr.trim();
      bStr.trim();

      newA = aStr.toInt();
      newB = bStr.toInt();

      aFound = true;
      bFound = true;
    }
  }

  if (aFound) {
    moveServoA(newA);
  }

  if (bFound) {
    moveServoB(newB);
  }

  int rawA = angleToServoRaw(inputA, CENTER_A, REVERSE_A);
  int rawB = angleToServoRaw(inputB, CENTER_B, REVERSE_B);

  Serial.print("Input A=");
  Serial.print(inputA);
  Serial.print(" -> Servo A raw=");
  Serial.print(rawA);

  Serial.print(" | Input B=");
  Serial.print(inputB);
  Serial.print(" -> Servo B raw=");
  Serial.println(rawB);
}