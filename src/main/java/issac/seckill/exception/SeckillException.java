package issac.seckill.exception;

/**
 *
 * author:  ywy
 * date:  2018-07-04
 * desc: 所有秒杀相关的业务异常
 *
 */
public class SeckillException extends RuntimeException {
    public SeckillException(String message) {
        super(message);
    }

    public SeckillException(String message, Throwable cause) {
        super(message, cause);
    }
}